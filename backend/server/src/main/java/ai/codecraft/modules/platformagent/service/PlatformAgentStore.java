package ai.codecraft.modules.platformagent.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import ai.codecraft.modules.platformagent.model.dto.PlatformActionRequest;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.platformagent.model.dto.PlatformCatalogResponse;
import ai.codecraft.modules.platformagent.model.dto.PlatformCheckResponse;
import ai.codecraft.modules.platformagent.model.dto.PlatformDefinitionDTO;
import ai.codecraft.modules.platformagent.model.dto.PlatformDraftRequest;
import ai.codecraft.modules.platformagent.model.dto.PlatformRoleResponse;
import ai.codecraft.modules.platformagent.model.dto.PlatformVersionResponse;
import ai.codecraft.modules.platformagent.model.entity.PlatformCheckEntity;
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.model.exception.PlatformAgentErrorCode;
import ai.codecraft.modules.platformagent.repository.PlatformAgentRepository;
import ai.codecraft.modules.platformagent.repository.PlatformCheckRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformAgentStore {
    private final PlatformAgentRepository repository;
    private final PlatformCheckRepository checks;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ModelCredentialCipher cipher;
    private final ModelConnectionValidator validator;
    private final PlatformDefinitions definitions;
    private final Clock clock;
    private final PlatformAgentProperties policy;
    private final PlatformCheckValidity validity;

    public PlatformAgentStore(PlatformAgentRepository repository, PlatformCheckRepository checks, IdentityTransactionService identity,
        IdentitySecrets secrets, ModelCredentialCipher cipher, ModelConnectionValidator validator, PlatformDefinitions definitions, Clock clock, PlatformAgentProperties policy, PlatformCheckValidity validity) {
        this.repository = repository; this.checks = checks; this.identity = identity; this.secrets = secrets;
        this.cipher = cipher; this.validator = validator; this.definitions = definitions; this.clock = clock; this.policy = policy; this.validity = validity;
    }

    @Transactional(readOnly = true)
    public PlatformCatalogResponse list(String session, String after) {
        admin(session, false);
        return new PlatformCatalogResponse(repository.list(validator.cursor(after)).stream().map(this::response).toList(),
            repository.roles().stream().map(value -> new PlatformRoleResponse(value.role(), value.currentVersion(), Long.toString(value.revision()))).toList());
    }

    @Transactional(readOnly = true)
    public PlatformVersionResponse get(String session, String id) { admin(session, false); return response(require(id)); }

    @Transactional
    public PlatformVersionResponse create(String session, String key, String id, PlatformDraftRequest input, PlatformDefinitionDTO definition) {
        String actor = admin(session, true).userId();
        String fingerprint = fingerprint(input);
        String previous = existing(actor, "CREATE", key, fingerprint);
        if (previous != null) return response(require(previous));
        repository.insert(id, input.role(), definitions.encode(definition), cipher.encrypt("platform", id, input.connection().apiKey()), clock.instant());
        repository.commandReceipt(actor, "CREATE", key, fingerprint, id);
        return response(require(id));
    }

    @Transactional
    public PlatformVersionResponse change(String session, String id, String kind, String key, PlatformActionRequest input) {
        String actor = admin(session, true).userId();
        if (!Set.of("PUBLISH", "RETIRE", "KEY").contains(kind) || input == null) throw invalid();
        String fingerprint = fingerprint(id + ":" + definitions.encode(input));
        String previous = existing(actor, kind, key, fingerprint);
        if (previous != null) return response(require(previous));
        var roles = repository.lockRoles();
        var version = repository.lock(id);
        if (version == null) throw missing();
        if (version.revision() != validator.version(input.expectedRevision())) throw conflict();
        var role = roles.stream().filter(value -> value.role() == version.role()).findFirst().orElseThrow();
        switch (kind) {
            case "PUBLISH" -> {
                if (!version.state().equals("DRAFT") || !Long.toString(role.revision()).equals(input.expectedRoleRevision())) throw conflict();
                validity.requireChecked(version, true);
                repository.publish(id, clock.instant()); repository.point(version.role(), id);
            }
            case "RETIRE" -> {
                repository.retire(id);
                if (id.equals(role.currentVersion())) repository.point(version.role(), null);
            }
            case "KEY" -> {
                if (version.state().equals("RETIRED") && repository.bindings(id) == 0) throw conflict();
                var connection = definitions.decode(version.definition()).connection();
                validator.connection(new ModelConnectionRequest(connection.name(), connection.baseUrl(),
                    connection.protocol(), connection.preset(), input.apiKey(), null), true);
                repository.credential(id, cipher.encrypt("platform", id, input.apiKey()));
            }
            default -> throw invalid();
        }
        repository.commandReceipt(actor, kind, key, fingerprint, id);
        return response(require(id));
    }

    @Transactional
    public PlatformCheckResponse check(String session, String id, String key, PlatformActionRequest input) {
        String actor = admin(session, true).userId();
        if (input == null || !Boolean.TRUE.equals(input.feeAcknowledged())) throw new BusinessException(PlatformAgentErrorCode.PLATFORM_CHECK_FEE_REQUIRED);
        String fingerprint = fingerprint(id + ":" + definitions.encode(input));
        String previous = existing(actor, "CHECK", key, fingerprint);
        if (previous != null) return checkResponse(checks.find(previous));
        repository.lockRoles();
        var version = repository.lock(id);
        if (version == null) throw missing();
        if (version.revision() != validator.version(input.expectedRevision())) throw conflict();
        if (version.credentialCiphertext() == null || version.state().equals("RETIRED") && repository.bindings(id) == 0) throw unavailable();
        if (checks.active(actor) != 0) throw new BusinessException(PlatformAgentErrorCode.PLATFORM_CHECK_ACTIVE);
        if (checks.recent(actor, clock.instant().minusSeconds(policy.checkWindowSeconds())) >= policy.checksPerWindow()) throw new BusinessException(PlatformAgentErrorCode.PLATFORM_CHECK_RATE_LIMITED);
        String check = UUID.randomUUID().toString();
        checks.insert(new PlatformCheckEntity(check, id, version.credentialRevision(), actor, session, "QUEUED", clock.instant(),
            clock.instant().plusSeconds(policy.queueSeconds()), null, null, null, null));
        repository.commandReceipt(actor, "CHECK", key, fingerprint, check);
        return checkResponse(checks.find(check));
    }

    @Transactional(readOnly = true)
    public PlatformCheckResponse latest(String session, String version) {
        admin(session, false); require(version);
        var result = checks.latest(version);
        if (result == null) throw new BusinessException(PlatformAgentErrorCode.PLATFORM_CHECK_NOT_FOUND);
        return checkResponse(result);
    }

    @Transactional
    public void reclaimCredentials() {
        repository.lockRoles();
        checks.prune(clock.instant().minusSeconds(Math.multiplyExact((long) policy.checkRetentionDays(), 86400)));
        repository.eraseRetiredCredentials();
    }

    private SessionAuthorizationDTO admin(String session, boolean mutation) {
        var auth = mutation ? identity.authorizeMutation(session) : identity.authorization(session);
        if (!auth.role().equals("ADMIN") || auth.passwordChangeRequired()) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        return auth;
    }

    private String existing(String actor, String kind, String key, String fingerprint) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw invalid();
        var receipt = repository.command(actor, kind, key);
        if (receipt == null) return null;
        if (!receipt.fingerprint().equals(fingerprint)) throw new BusinessException(PlatformAgentErrorCode.PLATFORM_IDEMPOTENCY_CONFLICT);
        return receipt.resourceId();
    }

    private String fingerprint(Object value) { return secrets.fingerprint(definitions.encode(value)); }
    private PlatformVersionEntity require(String id) { var value = repository.find(id); if (value == null) throw missing(); return value; }
    private PlatformVersionResponse response(PlatformVersionEntity value) {
        return new PlatformVersionResponse(value.id(), value.role(), value.state(), Long.toString(value.revision()), Long.toString(value.credentialRevision()),
            definitions.decode(value.definition()), value.credentialCiphertext() != null, value.createdAt(), value.publishedAt());
    }
    private PlatformCheckResponse checkResponse(PlatformCheckEntity value) {
        if (value == null) throw new BusinessException(PlatformAgentErrorCode.PLATFORM_CHECK_NOT_FOUND);
        return new PlatformCheckResponse(value.id(), value.versionId(), Long.toString(value.credentialRevision()), value.state(), value.createdAt(),
            value.finishedAt(), value.errorCode(), definitions.usage(value.usageReceipt()));
    }
    private BusinessException invalid() { return new BusinessException(PlatformAgentErrorCode.PLATFORM_INVALID_REQUEST); }
    private BusinessException conflict() { return new BusinessException(PlatformAgentErrorCode.PLATFORM_VERSION_CONFLICT); }
    private BusinessException missing() { return new BusinessException(PlatformAgentErrorCode.PLATFORM_VERSION_NOT_FOUND); }
    private BusinessException unavailable() { return new BusinessException(PlatformAgentErrorCode.PLATFORM_CONFIGURATION_UNAVAILABLE); }
}
