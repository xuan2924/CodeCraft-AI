package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCheckProperties;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckResponse;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckAuthorityEntity;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admission only: model dispatch must occur after this transaction commits. */
@Service
public class ModelCheckService {
    private final ModelCheckRepository checks;
    private final ModelConfigurationRepository models;
    private final ModelConnectionRepository connections;
    private final IdentityTransactionService identity;
    private final ModelCommandReceipts receipts;
    private final Clock clock;
    private final ModelCheckProperties properties;

    public ModelCheckService(ModelCheckRepository checks, ModelConfigurationRepository models, ModelConnectionRepository connections,
        IdentityTransactionService identity, ModelCommandReceipts receipts, Clock clock, ModelCheckProperties properties) {
        this.checks = checks; this.models = models; this.connections = connections;
        this.identity = identity; this.receipts = receipts; this.clock = clock; this.properties = properties;
    }

    @Transactional
    public ModelCheckResponse start(String session, String modelId, String key, boolean feeAcknowledged) {
        var grant = identity.authorizeMutation(session);
        if (!"USER".equals(grant.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        String owner = grant.userId();
        receipts.key(key);
        String fingerprint = receipts.fingerprint(feeAcknowledged);
        var previous = receipts.replay(owner, "START_MODEL_CHECK", modelId, key, fingerprint, ModelCheckResponse.class);
        if (previous != null) return previous;
        if (!feeAcknowledged) throw new BusinessException(ModelAccessErrorCode.MODEL_CHECK_FEE_ACKNOWLEDGEMENT_REQUIRED);
        var model = models.find(modelId);
        if (model == null || !model.userId().equals(owner) || model.deletedAt() != null) throw new BusinessException(ModelAccessErrorCode.MODEL_CONFIGURATION_NOT_FOUND);
        var connection = connections.find(model.connectionId());
        if (connection == null || !connection.userId().equals(owner) || connection.deletedAt() != null) throw new BusinessException(ModelAccessErrorCode.MODEL_CONNECTION_NOT_FOUND);
        if (checks.active(owner)) throw new BusinessException(ModelAccessErrorCode.MODEL_CHECK_ALREADY_ACTIVE);
        if (checks.startsSince(owner, clock.instant().minus(properties.startWindow())) >= properties.startsPerWindow()) throw new BusinessException(ModelAccessErrorCode.MODEL_CHECK_RATE_LIMITED);
        var check = new ModelCheckEntity(UUID.randomUUID().toString(), owner, connection.id(), model.id(), connection.configurationVersion(), model.configurationVersion(), "QUEUED", clock.instant());
        checks.insert(check);
        checks.insertAuthority(new ModelCheckAuthorityEntity(check.id(), owner, session, grant.authVersion(), grant.expiresAt(), null));
        return receipts.complete(owner, "START_MODEL_CHECK", modelId, key, fingerprint,
            new ModelCheckResponse(check.id(), model.id(), Long.toString(check.connectionConfigurationVersion()), Long.toString(check.modelConfigurationVersion()), check.status(), check.startedAt()));
    }
}
