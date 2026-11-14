package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCheckProperties;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckResponse;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckAuthorityEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckReuseEntity;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckStatusResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckItemResponse;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckResultEntity;
import java.util.Comparator;
import java.util.List;
import java.time.Instant;
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
    public ModelCheckStatusResponse status(String session, String id) {
        var grant = identity.authorizePrivateDelivery(session);
        if (!"USER".equals(grant.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        return describe(grant.userId(), id);
    }

    @Transactional
    public ModelCheckStatusResponse latest(String session, String modelId) {
        var grant = identity.authorizePrivateDelivery(session);
        if (!"USER".equals(grant.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        var model = models.find(modelId);
        if (model == null || model.deletedAt() != null || !model.userId().equals(grant.userId())) throw new BusinessException(ModelAccessErrorCode.MODEL_CONFIGURATION_NOT_FOUND);
        var id = checks.latestCheck(grant.userId(), modelId);
        if (id == null) throw new BusinessException(ModelAccessErrorCode.MODEL_CHECK_NOT_FOUND);
        return describe(grant.userId(), id);
    }

    private ModelCheckStatusResponse describe(String owner, String id) {
        var check = checks.find(id);
        if (check == null || !check.userId().equals(owner)) throw new BusinessException(ModelAccessErrorCode.MODEL_CHECK_NOT_FOUND);
        var model = models.find(check.modelConfigurationId());
        var connection = connections.find(check.connectionId());
        boolean current = model != null && connection != null && model.deletedAt() == null && connection.deletedAt() == null
            && model.userId().equals(owner) && connection.userId().equals(owner) && model.connectionId().equals(connection.id())
            && model.configurationVersion() == check.modelConfigurationVersion() && connection.configurationVersion() == check.connectionConfigurationVersion();
        var results = checks.results(id);
        String reason = checks.reason(id);
        boolean active = "QUEUED".equals(check.status()) || "RUNNING".equals(check.status());
        var summary = new ModelCheckResponse(check.id(), check.modelConfigurationId(), Long.toString(check.connectionConfigurationVersion()),
            Long.toString(check.modelConfigurationVersion()), check.status(), check.startedAt());
        var now = clock.instant();
        return new ModelCheckStatusResponse(summary, reason, current, item(results, false, active, current, reason, now), item(results, true, active, current, reason, now));
    }

    private ModelCheckItemResponse item(List<ModelCheckResultEntity> results, boolean tool, boolean active, boolean current, String reason, Instant now) {
        var result = results.stream().filter(value -> tool ? value.ordinal() >= 2 : value.ordinal() == 1)
            .max(Comparator.comparingInt(ModelCheckResultEntity::ordinal)).orElse(null);
        if (result == null) return new ModelCheckItemResponse(active ? "PENDING" : "NOT_RUN", null, null, false, false, reason);
        String status = result.status();
        if ("DISPATCHED".equals(status)) status = "RUNNING";
        if (tool && result.ordinal() == 2 && "SUCCEEDED".equals(status)) status = active ? "RUNNING" : "INCOMPLETE";
        boolean success = "SUCCEEDED".equals(status);
        var until = success && result.completedAt() != null ? result.completedAt().plus(ModelCheckProperties.SUCCESS_VALIDITY) : null;
        return new ModelCheckItemResponse(status, result.completedAt(), until, result.reused(), current && success && until != null && now.isBefore(until), success ? null : result.errorCode() == null ? reason : result.errorCode());
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
        var now = clock.instant();
        var reference = new ModelInvocationReferenceDTO(connection.id(), model.id(), connection.configurationVersion(), model.configurationVersion());
        var since = now.minus(ModelCheckProperties.SUCCESS_VALIDITY);
        String basic = checks.reusable(owner, reference, 1, since);
        String tool = checks.reusable(owner, reference, 3, since);
        String status = basic != null && tool != null ? "SUCCEEDED" : "QUEUED";
        var check = new ModelCheckEntity(UUID.randomUUID().toString(), owner, connection.id(), model.id(), connection.configurationVersion(), model.configurationVersion(), status, now);
        checks.insert(check);
        if (basic != null || tool != null) checks.insertReuse(new ModelCheckReuseEntity(check.id(), basic, tool));
        checks.insertAuthority(new ModelCheckAuthorityEntity(check.id(), owner, session, grant.authVersion(), grant.expiresAt(), null));
        return receipts.complete(owner, "START_MODEL_CHECK", modelId, key, fingerprint,
            new ModelCheckResponse(check.id(), model.id(), Long.toString(check.connectionConfigurationVersion()), Long.toString(check.modelConfigurationVersion()), check.status(), check.startedAt()));
    }
}
