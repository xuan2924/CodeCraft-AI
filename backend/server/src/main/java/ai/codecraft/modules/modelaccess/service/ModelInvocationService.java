package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationResolutionDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.EncryptedCredentialDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves an authorized immutable call snapshot, never performs network IO or retains a historical key. */
@Service
public class ModelInvocationService {
    private final IdentityTransactionService identity;
    private final ModelConnectionRepository connections;
    private final ModelConfigurationRepository models;
    private final ModelCredentialCipher cipher;

    public ModelInvocationService(IdentityTransactionService identity, ModelConnectionRepository connections,
        ModelConfigurationRepository models, ModelCredentialCipher cipher) {
        this.identity = identity; this.connections = connections; this.models = models; this.cipher = cipher;
    }

    @Transactional
    public ModelInvocationDTO resolve(String session, ModelInvocationReferenceDTO reference) {
        return resolve(identity.authorizeMutation(session), reference);
    }

    /** Expected invalidation does not mark a caller's recovery transaction rollback-only. */
    @Transactional
    public ModelInvocationResolutionDTO tryResolve(String session, ModelInvocationReferenceDTO reference) {
        var grant = identity.tryAuthorizeMutation(session);
        if (grant == null) return new ModelInvocationResolutionDTO(null, IdentityErrorCode.UNAUTHENTICATED);
        try { return new ModelInvocationResolutionDTO(resolve(grant, reference), null); }
        catch (BusinessException unavailable) { return new ModelInvocationResolutionDTO(null, unavailable.errorCode()); }
    }

    private ModelInvocationDTO resolve(SessionAuthorizationDTO grant, ModelInvocationReferenceDTO reference) {
        if (!"USER".equals(grant.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        var model = models.find(reference.modelConfigurationId());
        if (model == null || !model.userId().equals(grant.userId()) || model.deletedAt() != null) throw new BusinessException(ModelAccessErrorCode.MODEL_CONFIGURATION_NOT_FOUND);
        var connection = connections.find(reference.connectionId());
        if (connection == null || !connection.userId().equals(grant.userId()) || connection.deletedAt() != null) throw new BusinessException(ModelAccessErrorCode.MODEL_CONNECTION_NOT_FOUND);
        if (!model.connectionId().equals(connection.id()) || connection.configurationVersion() != reference.connectionConfigurationVersion() || model.configurationVersion() != reference.modelConfigurationVersion()) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_VERSION_CONFLICT);
        }
        var secret = cipher.decrypt(grant.userId(), connection.id(), new EncryptedCredentialDTO(connection.credentialKeyId(), connection.credentialNonce(), connection.credentialCiphertext()));
        return new ModelInvocationDTO(
            new ModelConnectionResponse(connection.id(), connection.name(), connection.baseUrl(), ModelProtocol.valueOf(connection.protocol()), ModelProviderPreset.valueOf(connection.preset()),
                Long.toString(connection.version()), Long.toString(connection.configurationVersion()), true, false),
            new ModelConfigurationResponse(model.id(), model.connectionId(), model.name(), model.modelId(), model.contextWindow(), model.maxOutputTokens(), ModelThinkingMode.valueOf(model.thinkingMode()),
                model.reasoningEffort(), model.thinkingBudgetTokens(), model.temperature(), Long.toString(model.version()), Long.toString(model.configurationVersion()), "USER_PROVIDED", false), secret);
    }
}
