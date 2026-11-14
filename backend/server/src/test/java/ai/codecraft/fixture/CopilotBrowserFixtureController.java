package ai.codecraft.fixture;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.service.ModelConnectionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Test-only personal connection provisioning: the provider credential never reaches the browser driver. */
@RestController
@ConditionalOnProperty(name = "COPILOT_BROWSER_E2E", havingValue = "disposable")
public class CopilotBrowserFixtureController {
    private final ModelConnectionService connections;
    private final Environment environment;

    public CopilotBrowserFixtureController(ModelConnectionService connections, Environment environment, CopilotBrowserBudget budget, ModelCallDriver driver) {
        if (!budget.guards(driver)) throw new IllegalStateException("Live browser dispatch budget was not installed");
        this.connections = connections; this.environment = environment;
    }

    @PostMapping("/api/test-fixtures/copilot-connection")
    public ResponseEntity<ApiResponse<ModelConnectionResponse>> create(HttpServletRequest request,
        @RequestHeader("Idempotency-Key") String key) {
        var credential = environment.getProperty("DEEPSEEK");
        if (credential == null || credential.isBlank()) throw new IllegalStateException("Live fixture credential is unavailable");
        var input = new ModelConnectionRequest("DeepSeek real browser fixture", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT,
            ModelProviderPreset.DEEPSEEK, credential, null);
        var result = connections.create(IdentityHttp.sessionHash(request), key, input);
        return ResponseEntity.created(URI.create("/api/model-connections/" + result.id())).header("Cache-Control", "no-store").body(ApiResponse.success(result));
    }
}
