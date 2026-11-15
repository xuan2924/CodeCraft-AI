package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotReferencePageDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotReferenceSourcesDTO;
import ai.codecraft.modules.copilot.service.CopilotReferenceService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotReferenceController {
    private final CopilotReferenceService references;
    public CopilotReferenceController(CopilotReferenceService references) { this.references = references; }
    @GetMapping("/api/exercises/{exercise}/copilot/conversations/{conversation}/references/sources")
    public ApiResponse<CopilotReferenceSourcesDTO> sources(@PathVariable UUID exercise, @PathVariable UUID conversation,
        @RequestParam String kind, @RequestParam(required = false) String after, HttpServletRequest request) {
        return ApiResponse.success(references.sources(IdentityHttp.sessionHash(request), exercise.toString(), conversation.toString(), kind, after));
    }
    @GetMapping("/api/exercises/{exercise}/copilot/conversations/{conversation}/references")
    public ApiResponse<CopilotReferencePageDTO> preview(@PathVariable UUID exercise, @PathVariable UUID conversation,
        @RequestParam String kind, @RequestParam String source, @RequestParam(required = false) String version,
        @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "2048") int limit, HttpServletRequest request) {
        return ApiResponse.success(references.preview(IdentityHttp.sessionHash(request), exercise.toString(), conversation.toString(), kind, source, version, offset, limit));
    }
}
