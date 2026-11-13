package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.modelaccess.model.dto.ModelCommandResponse;
import ai.codecraft.modules.modelaccess.model.entity.ModelCommandEntity;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class ModelCommandReceipts {
    private final ModelConnectionRepository repository;
    private final IdentitySecrets secrets;
    private final ObjectMapper json;
    private final Clock clock;

    public ModelCommandReceipts(ModelConnectionRepository repository, IdentitySecrets secrets, ObjectMapper json, Clock clock) {
        this.repository = repository; this.secrets = secrets; this.json = json; this.clock = clock;
    }

    public String fingerprint(Object value) {
        try { return secrets.fingerprint(json.writeValueAsString(value)); }
        catch (JsonProcessingException malformed) { throw new IllegalStateException("Cannot fingerprint model configuration", malformed); }
    }

    public void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
    }

    public <T extends ModelCommandResponse> T replay(String owner, String kind, String scope, String key, String fingerprint, Class<T> type) {
        var receipt = repository.command(owner, kind, scope, key);
        if (receipt == null) return null;
        if (!receipt.fingerprint().equals(fingerprint)) throw new BusinessException(ModelAccessErrorCode.MODEL_IDEMPOTENCY_CONFLICT);
        if (receipt.response() == null) throw new BusinessException(ModelAccessErrorCode.MODEL_RECEIPT_EXPIRED);
        try { return json.readValue(receipt.response(), type); }
        catch (JsonProcessingException malformed) { throw new IllegalStateException("Cannot read model configuration receipt", malformed); }
    }

    public <T extends ModelCommandResponse> T complete(String owner, String kind, String scope, String key, String fingerprint, T result) {
        try {
            repository.insertCommand(new ModelCommandEntity(owner, kind, scope, key, fingerprint, json.writeValueAsString(result), clock.instant()));
            return result;
        } catch (JsonProcessingException malformed) { throw new IllegalStateException("Cannot encode model configuration receipt", malformed); }
    }
}
