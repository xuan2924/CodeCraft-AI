package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProjectInstructionsDTO;
import ai.codecraft.modules.copilot.service.CopilotProjectInstructions;
import ai.codecraft.modules.copilot.service.CopilotTaskStore;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotProjectInstructionsController {
    private final CopilotTaskStore tasks;
    private final CopilotProjectInstructions instructions;
    public CopilotProjectInstructionsController(CopilotTaskStore tasks, CopilotProjectInstructions instructions) {
        this.tasks = tasks; this.instructions = instructions;
    }
    @GetMapping("/api/copilot-tasks/{task}/project-instructions")
    public ApiResponse<CopilotProjectInstructionsDTO> current(@PathVariable UUID task, HttpServletRequest request) {
        return ApiResponse.success(instructions.decode(tasks.projectInstructions(IdentityHttp.sessionHash(request), task.toString())));
    }
}
