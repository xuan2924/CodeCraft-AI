package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerPreparedDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotSteersResponse;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class CopilotSteerService {
    private final CopilotSteerStore store;
    private final CopilotTaskStore tasks;
    private final StorageCapacityService capacity;
    private final IdentitySecrets secrets;
    public CopilotSteerService(CopilotSteerStore store, CopilotTaskStore tasks, StorageCapacityService capacity, IdentitySecrets secrets) {
        this.store = store; this.tasks = tasks; this.capacity = capacity; this.secrets = secrets;
    }
    public CopilotSteersResponse list(String session, String task, long before) { return store.list(session, task, before); }
    public CopilotSteerResponse accept(String session, String task, String credential, String key, CopilotSteerRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || request == null || request.text() == null || request.text().isBlank()
            || request.text().getBytes(StandardCharsets.UTF_8).length > 4096 || request.text().indexOf(0) >= 0
            || !StandardCharsets.UTF_8.newEncoder().canEncode(request.text())) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        var input = new CopilotSteerPreparedDTO(request.text(), ChatMessageSerializer.messageToJson(UserMessage.from(request.text())), secrets.fingerprint(request.text()));
        var previous = store.replay(session, task, key, input.fingerprint());
        if (previous != null) return previous;
        String exercise = tasks.find(session, task).exerciseId();
        return capacity.admit(session, exercise, measured -> store.accept(session, task, credential, key, input, measured));
    }
}
