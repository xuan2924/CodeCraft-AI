package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationsResponse;
import ai.codecraft.modules.modelaccess.model.entity.ModelConfigurationEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelConnectionEntity;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConfigurationService {
    private final ModelConfigurationRepository models;
    private final ModelConnectionRepository connections;
    private final IdentityTransactionService identity;
    private final ModelConnectionValidator validator;
    private final ModelCommandReceipts receipts;
    private final Clock clock;

    public ModelConfigurationService(ModelConfigurationRepository models, ModelConnectionRepository connections, IdentityTransactionService identity,
        ModelConnectionValidator validator, ModelCommandReceipts receipts, Clock clock) {
        this.models = models; this.connections = connections; this.identity = identity;
        this.validator = validator; this.receipts = receipts; this.clock = clock;
    }

    @Transactional
    public ModelConfigurationResponse create(String session, String connection, String key, ModelConfigurationRequest input) {
        String owner = user(identity.authorizeMutation(session));
        receipts.key(key);
        String fingerprint = receipts.fingerprint(input);
        var previous = receipts.replay(owner, "CREATE_MODEL", connection, key, fingerprint, ModelConfigurationResponse.class);
        if (previous != null) return previous;
        var parent = connection(owner, connection);
        validator.configuration(input, ModelProtocol.valueOf(parent.protocol()));
        String name = validator.name(input.name());
        if (models.nameExists(connection, name, null)) throw new BusinessException(ModelAccessErrorCode.MODEL_NAME_CONFLICT);
        var now = clock.instant();
        var value = new ModelConfigurationEntity(UUID.randomUUID().toString(), connection, owner, name, input.modelId(), input.contextWindow(), input.maxOutputTokens(),
            input.thinkingMode().name(), input.reasoningEffort(), input.thinkingBudgetTokens(), input.temperature(), 1, 1, now, now, null);
        models.insert(value);
        return receipts.complete(owner, "CREATE_MODEL", connection, key, fingerprint, describe(value));
    }

    @Transactional
    public ModelConfigurationResponse configuration(String session, String id) {
        String owner = user(identity.authorizePrivateDelivery(session));
        var value = owned(owner, id);
        connection(owner, value.connectionId());
        return describe(value);
    }

    @Transactional
    public ModelConfigurationResponse update(String session, String id, String key, ModelConfigurationRequest input) {
        String owner = user(identity.authorizeMutation(session));
        receipts.key(key);
        String fingerprint = receipts.fingerprint(input);
        var previous = receipts.replay(owner, "UPDATE_MODEL", id, key, fingerprint, ModelConfigurationResponse.class);
        if (previous != null) return previous;
        var current = owned(owner, id);
        var parent = connection(owner, current.connectionId());
        validator.configuration(input, ModelProtocol.valueOf(parent.protocol()));
        if (validator.version(input.expectedVersion()) != current.version()) throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        String name = validator.name(input.name());
        if (models.nameExists(current.connectionId(), name, id)) throw new BusinessException(ModelAccessErrorCode.MODEL_NAME_CONFLICT);
        boolean changed = !current.modelId().equals(input.modelId()) || current.contextWindow() != input.contextWindow() || current.maxOutputTokens() != input.maxOutputTokens()
            || !current.thinkingMode().equals(input.thinkingMode().name()) || !Objects.equals(current.reasoningEffort(), input.reasoningEffort())
            || !Objects.equals(current.thinkingBudgetTokens(), input.thinkingBudgetTokens()) || !Objects.equals(current.temperature(), input.temperature());
        var value = new ModelConfigurationEntity(id, current.connectionId(), owner, name, input.modelId(), input.contextWindow(), input.maxOutputTokens(), input.thinkingMode().name(),
            input.reasoningEffort(), input.thinkingBudgetTokens(), input.temperature(), Math.incrementExact(current.version()), changed ? Math.incrementExact(current.configurationVersion()) : current.configurationVersion(),
            current.createdAt(), clock.instant(), null);
        if (models.update(value, current.version()) != 1) throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        return receipts.complete(owner, "UPDATE_MODEL", id, key, fingerprint, describe(value));
    }
    @Transactional
    public ModelConfigurationResponse delete(String session, String id, String key, String version) {
        String owner = user(identity.authorizeMutation(session));
        receipts.key(key);
        String fingerprint = receipts.fingerprint(version);
        var previous = receipts.replay(owner, "DELETE_MODEL", id, key, fingerprint, ModelConfigurationResponse.class);
        if (previous != null) return previous;
        var current = ownedIncludingDeleted(owner, id);
        if (current.deletedAt() != null) return describe(current);
        if (validator.version(version) != current.version()) throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        var now = clock.instant();
        var value = new ModelConfigurationEntity(id, current.connectionId(), owner, current.name(), current.modelId(), current.contextWindow(), current.maxOutputTokens(),
            current.thinkingMode(), current.reasoningEffort(), current.thinkingBudgetTokens(), current.temperature(), Math.incrementExact(current.version()),
            Math.incrementExact(current.configurationVersion()), current.createdAt(), now, now);
        if (models.update(value, current.version()) != 1) throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        return receipts.complete(owner, "DELETE_MODEL", id, key, fingerprint, describe(value));
    }
    @Transactional
    public ModelConfigurationsResponse configurations(String session, String connection, String after) {
        String owner = user(identity.authorizePrivateDelivery(session));
        connection(owner, connection);
        var values = models.list(owner, connection, validator.cursor(after));
        int size = Math.min(20, values.size());
        return new ModelConfigurationsResponse(values.subList(0, size).stream().map(this::describe).toList(), values.size() > 20 ? values.get(size - 1).id() : null);
    }

    private ModelConfigurationEntity owned(String owner, String id) {
        var value = ownedIncludingDeleted(owner, id);
        if (value.deletedAt() != null) throw new BusinessException(ModelAccessErrorCode.MODEL_CONFIGURATION_NOT_FOUND);
        return value;
    }

    private ModelConfigurationEntity ownedIncludingDeleted(String owner, String id) {
        var value = models.find(id);
        if (value == null || !value.userId().equals(owner)) throw new BusinessException(ModelAccessErrorCode.MODEL_CONFIGURATION_NOT_FOUND);
        return value;
    }

    private ModelConnectionEntity connection(String owner, String id) {
        var parent = connections.find(id);
        if (parent == null || !parent.userId().equals(owner) || parent.deletedAt() != null) throw new BusinessException(ModelAccessErrorCode.MODEL_CONNECTION_NOT_FOUND);
        return parent;
    }

    private String user(SessionAuthorizationDTO grant) {
        if (!grant.role().equals("USER")) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        return grant.userId();
    }

    private ModelConfigurationResponse describe(ModelConfigurationEntity value) {
        return new ModelConfigurationResponse(value.id(), value.connectionId(), value.name(), value.modelId(), value.contextWindow(), value.maxOutputTokens(),
            ModelThinkingMode.valueOf(value.thinkingMode()), value.reasoningEffort(), value.thinkingBudgetTokens(), value.temperature(), Long.toString(value.version()),
            Long.toString(value.configurationVersion()), "USER_PROVIDED", value.deletedAt() != null);
    }
}
