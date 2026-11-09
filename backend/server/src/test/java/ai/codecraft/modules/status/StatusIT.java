package ai.codecraft.modules.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// Explicit opt-in: scripts/test-tracer.mjs provisions a disposable real database and Go process.
@EnabledIfEnvironmentVariable(named = "TRACER_IT_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class StatusIT {
    @Test
    void realDatabaseAndGoAreAvailableThroughJava() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                System.getenv("TRACER_IT_URL") + "/api/internal/status")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"database\":\"AVAILABLE\",\"sandbox\":\"AVAILABLE\"}");
            assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        }
    }
}
