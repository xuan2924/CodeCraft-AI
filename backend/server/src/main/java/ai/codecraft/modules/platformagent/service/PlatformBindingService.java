package ai.codecraft.modules.platformagent.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.model.dto.EncryptedCredentialDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.dto.PlatformDefinitionDTO;
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.model.exception.PlatformAgentErrorCode;
import ai.codecraft.modules.platformagent.repository.PlatformAgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trusted domain access only. Callers own exercise authorization and dispatch admission. */
@Service
public class PlatformBindingService {
    private final PlatformAgentRepository repository;
    private final PlatformCheckValidity validity;
    private final PlatformDefinitions definitions;
    private final ModelCredentialCipher cipher;

    public PlatformBindingService(PlatformAgentRepository repository, PlatformCheckValidity validity, PlatformDefinitions definitions, ModelCredentialCipher cipher) {
        this.repository = repository; this.validity = validity; this.definitions = definitions; this.cipher = cipher;
    }

    @Transactional
    public void bind(String exercise) {
        var roles = repository.lockRoles();
        if (repository.bound(exercise, PlatformRole.INTERVIEWER) != null && repository.bound(exercise, PlatformRole.EVALUATOR) != null) return;
        if (roles.size() != PlatformRole.values().length) throw unavailable();
        for (var role : roles) {
            var version = role.currentVersion() == null ? null : repository.lock(role.currentVersion());
            if (version == null || !version.state().equals("PUBLISHED") || version.credentialCiphertext() == null) throw unavailable();
            validity.requireChecked(version, false);
            repository.bind(exercise, role.role(), version.id());
        }
    }

    @Transactional(readOnly = true)
    public PlatformVersionEntity version(String exercise, PlatformRole role) {
        var version = repository.bound(exercise, role);
        if (version == null || version.credentialCiphertext() == null) throw unavailable();
        return version;
    }

    public ModelInvocationDTO invocation(PlatformVersionEntity version) {
        PlatformDefinitionDTO definition = definitions.decode(version.definition());
        if (!PlatformDefinitions.POLICY_VERSION.equals(definition.policyVersion())) throw unavailable();
        String credential = cipher.decrypt("platform", version.id(), new EncryptedCredentialDTO(version.credentialKeyId(), version.credentialNonce(), version.credentialCiphertext()));
        return new ModelInvocationDTO(definition.connection(), definition.model(), credential);
    }

    @Transactional(readOnly = true)
    public long retainedBytes(String exercise) { return repository.bindingBytes(exercise); }

    @Transactional
    public void erase(String exercise) { repository.eraseBindings(exercise); }

    private BusinessException unavailable() { return new BusinessException(PlatformAgentErrorCode.PLATFORM_CONFIGURATION_UNAVAILABLE); }
}
