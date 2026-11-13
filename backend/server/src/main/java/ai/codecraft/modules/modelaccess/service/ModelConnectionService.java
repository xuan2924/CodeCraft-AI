package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionsResponse;
import ai.codecraft.modules.modelaccess.model.entity.ModelConnectionEntity;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConnectionService {
    private final ModelConnectionRepository repository;
    private final IdentityTransactionService identity;
    private final ModelCommandReceipts receipts;
    private final ModelCredentialCipher cipher;
    private final ModelConnectionValidator validator;
    private final Clock clock;

    public ModelConnectionService(ModelConnectionRepository repository, IdentityTransactionService identity, ModelCredentialCipher cipher,
        ModelConnectionValidator validator, ModelCommandReceipts receipts, Clock clock) {
        this.repository = repository; this.identity = identity; this.receipts = receipts;
        this.cipher = cipher; this.validator = validator; this.clock = clock;
    }

    @Transactional
    public ModelConnectionResponse create(String session, String key, ModelConnectionRequest input) {
        String owner = user(identity.authorizeMutation(session));
        receipts.key(key);
        validator.connection(input, true);
        String fingerprint = receipts.fingerprint(input);
        var previous = receipts.replay(owner, "CREATE_CONNECTION", "connections", key, fingerprint, ModelConnectionResponse.class);
        if (previous != null) return previous;
        String name = validator.name(input.name());
        if (repository.nameExists(owner, name, null)) throw new BusinessException(ModelAccessErrorCode.MODEL_NAME_CONFLICT);
        String id = UUID.randomUUID().toString();
        var sealed = cipher.encrypt(owner, id, input.apiKey());
        var now = clock.instant();
        var value = new ModelConnectionEntity(id, owner, name, input.baseUrl(), input.protocol().name(), input.preset().name(),
            sealed.keyId(), sealed.nonce(), sealed.ciphertext(), 1, 1, now, now, null);
        repository.insert(value);
        return receipts.complete(owner, "CREATE_CONNECTION", "connections", key, fingerprint, describe(value));
    }

    // Private delivery takes the identity owner's lock, hence a normal transaction even for this read.
    @Transactional
    public ModelConnectionResponse connection(String session, String id) {
        String owner = user(identity.authorizePrivateDelivery(session));
        return describe(owned(owner, id));
    }

    @Transactional
    public ModelConnectionResponse update(String session, String id, String key, ModelConnectionRequest input) {
        String owner = user(identity.authorizeMutation(session));
        receipts.key(key);
        validator.connection(input, false);
        String fingerprint = receipts.fingerprint(input);
        var previous = receipts.replay(owner, "UPDATE_CONNECTION", id, key, fingerprint, ModelConnectionResponse.class);
        if (previous != null) return previous;
        var current = owned(owner, id);
        if (validator.version(input.expectedVersion()) != current.version()) throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        String name = validator.name(input.name());
        if (repository.nameExists(owner, name, id)) throw new BusinessException(ModelAccessErrorCode.MODEL_NAME_CONFLICT);
        var credential = input.apiKey() == null ? current.credential() : cipher.encrypt(owner, id, input.apiKey());
        boolean changed = input.apiKey() != null || !current.baseUrl().equals(input.baseUrl()) || !current.protocol().equals(input.protocol().name()) || !current.preset().equals(input.preset().name());
        var value = new ModelConnectionEntity(id, owner, name, input.baseUrl(), input.protocol().name(), input.preset().name(), credential.keyId(), credential.nonce(), credential.ciphertext(),
            Math.incrementExact(current.version()), changed ? Math.incrementExact(current.configurationVersion()) : current.configurationVersion(), current.createdAt(), clock.instant(), null);
        if (repository.update(value, current.version()) != 1) throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        return receipts.complete(owner, "UPDATE_CONNECTION", id, key, fingerprint, describe(value));
    }

    @Transactional
    public ModelConnectionResponse delete(String session, String id, String key, String expectedVersion) {
        String owner = user(identity.authorizeMutation(session));
        receipts.key(key);
        String fingerprint = receipts.fingerprint(expectedVersion);
        var previous = receipts.replay(owner, "DELETE_CONNECTION", id, key, fingerprint, ModelConnectionResponse.class);
        if (previous != null) return previous;
        var current = ownedIncludingDeleted(owner, id);
        if (current.deletedAt() != null) return describe(current);
        if (validator.version(expectedVersion) != current.version()) throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        var now = clock.instant();
        var value = new ModelConnectionEntity(id, owner, current.name(), current.baseUrl(), current.protocol(), current.preset(), null, null, null,
            Math.incrementExact(current.version()), Math.incrementExact(current.configurationVersion()), current.createdAt(), now, now);
        if (repository.update(value, current.version()) != 1) throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        repository.retireModels(owner, id, now);
        return receipts.complete(owner, "DELETE_CONNECTION", id, key, fingerprint, describe(value));
    }

    @Transactional
    public ModelConnectionsResponse connections(String session, String after) {
        String owner = user(identity.authorizePrivateDelivery(session));
        var values = repository.list(owner, validator.cursor(after));
        int size = Math.min(20, values.size());
        return new ModelConnectionsResponse(values.subList(0, size).stream().map(this::describe).toList(), values.size() > 20 ? values.get(size - 1).id() : null);
    }

    @Transactional
    public void expireReceipts() { repository.expireReceipts(clock.instant().minus(Duration.ofDays(7))); }

    private ModelConnectionEntity owned(String owner, String id) {
        var value = ownedIncludingDeleted(owner, id);
        if (value.deletedAt() != null) throw new BusinessException(ModelAccessErrorCode.MODEL_CONNECTION_NOT_FOUND);
        return value;
    }

    private ModelConnectionEntity ownedIncludingDeleted(String owner, String id) {
        var value = repository.find(id);
        if (value == null || !value.userId().equals(owner)) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_CONNECTION_NOT_FOUND);
        }
        return value;
    }

    private String user(SessionAuthorizationDTO grant) {
        if (!grant.role().equals("USER")) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        return grant.userId();
    }

    private ModelConnectionResponse describe(ModelConnectionEntity value) {
        return new ModelConnectionResponse(value.id(), value.name(), value.baseUrl(), ModelProtocol.valueOf(value.protocol()), ModelProviderPreset.valueOf(value.preset()),
            Long.toString(value.version()), Long.toString(value.configurationVersion()), value.credentialCiphertext() != null, value.deletedAt() != null);
    }
}
