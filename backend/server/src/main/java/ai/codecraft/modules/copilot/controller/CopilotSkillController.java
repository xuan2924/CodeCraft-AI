package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.service.CopilotSkillService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import ai.codecraft.modules.skills.model.dto.SkillSnapshotDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotSkillController {
    private final CopilotSkillService skills;
    private final ObjectMapper json;
    public CopilotSkillController(CopilotSkillService skills, @Qualifier("skillJson") ObjectMapper json) { this.skills = skills; this.json = json; }
    @GetMapping("/api/copilot-tasks/{task}/skills")
    public ApiResponse<List<SkillSnapshotDTO>> current(@PathVariable UUID task, HttpServletRequest request) {
        return ApiResponse.success(skills.current(IdentityHttp.sessionHash(request), task.toString()));
    }
    @GetMapping("/api/copilot-tasks/{task}/skills/{skill}/package")
    public ApiResponse<SkillPackageRequest> source(@PathVariable UUID task, @PathVariable UUID skill, HttpServletRequest request) throws JsonProcessingException {
        var source = skills.source(IdentityHttp.sessionHash(request), task.toString(), skill.toString());
        return ApiResponse.success(json.readValue(source.filesPayload(), SkillPackageRequest.class));
    }
}
