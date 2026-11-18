package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotTelemetryResponse;
import ai.codecraft.modules.copilot.service.CopilotTelemetryService;
import ai.codecraft.modules.identity.security.IdentityHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CopilotTelemetryController {
    private final CopilotTelemetryService telemetry;

    public CopilotTelemetryController(CopilotTelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    @GetMapping("/api/exercises/{exerciseId}/copilot/telemetry")
    public ApiResponse<CopilotTelemetryResponse> exercise(@PathVariable UUID exerciseId, HttpServletRequest request) {
        return ApiResponse.success(telemetry.exercise(IdentityHttp.sessionHash(request), exerciseId.toString()));
    }

    @GetMapping("/api/admin/metrics/copilot")
    public ApiResponse<CopilotTelemetryResponse.Totals> platform() {
        return ApiResponse.success(telemetry.platform());
    }
}
