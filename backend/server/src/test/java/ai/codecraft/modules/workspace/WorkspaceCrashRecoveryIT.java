package ai.codecraft.modules.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.testfixture.WorkspaceCrashApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** REST assertions against real child JVMs, PostgreSQL and Go; no database result assertions. */
@EnabledIfEnvironmentVariable(named = "WORKSPACE_CRASH_IT_RUN", matches = "disposable")
class WorkspaceCrashRecoveryIT {
    private final ObjectMapper json = new ObjectMapper();
    private int port;
    private String csrf;
    private Process application;
    private HttpClient http;

    @Test
    void committedBatchSurvivesJavaDeathBeforeReceiptPersistenceWithoutReplay() throws Exception {
        Path evidence = Files.createTempDirectory(Path.of("target"), "workspace-crash-").toAbsolutePath();
        Path committed = evidence.resolve("committed.txt");
        Path replayed = evidence.resolve("replayed.txt");
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build()) {
            http = client;
            Throwable failure = null;
            try {
                start("crash", committed, evidence.resolve("before.log"));
                String username = System.getenv("BOOTSTRAP_ADMIN_USERNAME");
                String password = System.getenv("BOOTSTRAP_ADMIN_PASSWORD");
                login(username, password);
                String newPassword = UUID.randomUUID() + "-recovery";
                request("POST", "/api/auth/password", Map.of("currentPassword", password, "newPassword", newPassword), "change-password", null, 204);
                login(username, newPassword);
                String problem = request("GET", "/api/problems", null, null, null, 200).path("data").get(0).path("problemVersionId").asText();
                String exercise = request("POST", "/api/exercises", Map.of("problemVersionId", problem), "create", null, 202).path("data").path("exerciseId").asText();
                String base = "/api/exercises/" + exercise;
                awaitState(base, "READY");
                String credential = request("POST", base + "/control/acquire", Map.of(), "control", null, 200).path("data").path("credential").asText();
                request("POST", base + "/start", Map.of(), "start", credential, 200);
                try {
                    HttpResponse<String> response = send("POST", base + "/file-batches", Map.of("expectedRevision", "0", "changes", List.of(
                        Map.of("path", "src/test/java/Recovered.java", "kind", "CREATE", "content", "class Recovered {}"))), "write-once", credential);
                    assertThat(response.statusCode()).isEqualTo(202);
                } catch (IOException expectedConnectionLoss) {
                    // A lost HTTP response is expected, but is not evidence of the Go commit or successful recovery.
                }
                assertThat(application.waitFor(20, TimeUnit.SECONDS)).as("child must reach precise hard-crash point").isTrue();
                assertThat(application.exitValue()).isEqualTo(137);
                List<String> receipt = Files.readAllLines(committed);
                assertThat(receipt).hasSize(2);
                assertThat(receipt.get(1)).isEqualTo("1");
                String operation = receipt.get(0);
                assertThat(operation).matches("[0-9a-f-]{36}");
                start("recover", replayed, evidence.resolve("after.log"));
                JsonNode recovered = awaitState("/api/operations/" + operation, "SUCCEEDED");
                assertThat(recovered.path("operationId").asText()).isEqualTo(operation);
                assertThat(recovered.path("revision").asText()).isEqualTo("1");
                JsonNode file = request("GET", base + "/files?path=src/test/java/Recovered.java", null, null, null, 200).path("data");
                assertThat(file.path("revision").asText()).isEqualTo("1");
                assertThat(file.path("content").asText()).isEqualTo("class Recovered {}");
                assertThat(request("GET", base, null, null, null, 200).path("data").path("revision").asText()).isEqualTo("1");
                assertThat(Files.exists(replayed)).as("recovery must never call ApplyBatch again").isFalse();
                // Only GETs drove file recovery. Now crash another real JVM after
                // Go accepted StartTest but before Java persists its first result.
                stop();
                Path taskAccepted = evidence.resolve("task-accepted.txt");
                Path taskReplayed = evidence.resolve("task-replayed.txt");
                start("task-crash", taskAccepted, evidence.resolve("task-before.log"));
                try {
                    assertThat(send("POST", base + "/tests", Map.of("expectedRevision", "1", "target", "base"), "test-once", credential).statusCode()).isEqualTo(202);
                } catch (IOException expectedConnectionLoss) { /* Durable receipt below is the acceptance evidence. */ }
                assertThat(application.waitFor(25, TimeUnit.SECONDS)).as("StartTest commit crash barrier").isTrue();
                assertThat(application.exitValue()).isEqualTo(137);
                List<String> taskReceipt = Files.readAllLines(taskAccepted);
                assertThat(taskReceipt).hasSize(2);
                start("task-recover", taskReplayed, evidence.resolve("task-after.log"));
                JsonNode taskResult = awaitState(base + "/tasks/" + taskReceipt.get(0), "FAILED");
                assertThat(taskResult.path("taskId").asText()).isEqualTo(taskReceipt.get(0));
                assertThat(taskResult.path("executionInstanceId").asText()).isEqualTo(taskReceipt.get(1));
                assertThat(taskResult.path("revision").asText()).isEqualTo("1");
                assertThat(taskResult.path("cursor").asText()).isNotBlank();
                assertThat(request("GET", base + "/tasks", null, null, null, 200).path("data")).hasSize(1);
                assertThat(Files.exists(taskReplayed)).as("Java restart must never replay StartTest").isFalse();
            } catch (Exception | Error error) {
                failure = error;
                throw error;
            } finally {
                try { stop(); }
                catch (Exception | Error cleanupError) {
                    if (failure != null) failure.addSuppressed(cleanupError);
                    else throw cleanupError;
                }
            }
        }
    }

    private void login(String username, String password) throws Exception {
        csrf = request("GET", "/api/auth/csrf", null, null, null, 200).path("data").path("token").asText();
        String captcha = request("POST", "/api/auth/captcha", Map.of(), null, null, 200).path("data").path("id").asText();
        request("POST", "/api/auth/login", Map.of("username", username, "password", password,
            "captchaId", captcha, "captchaAnswer", "ABCDE"), null, null, 200);
        csrf = request("GET", "/api/auth/csrf", null, null, null, 200).path("data").path("token").asText();
    }

    private void start(String mode, Path marker, Path log) throws Exception {
        application = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("surefire.test.class.path"), WorkspaceCrashApplication.class.getName(),
            mode, marker.toString(), Integer.toString(port)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && application.isAlive()) {
            try {
                if (send("GET", "/api/internal/status", null, null, null).statusCode() == 200) return;
            } catch (IOException notReady) { /* bounded readiness polling */ }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Child readiness failed; inspect " + log);
    }

    private void stop() throws Exception {
        if (application == null || !application.isAlive()) return;
        application.destroyForcibly();
        assertThat(application.waitFor(10, TimeUnit.SECONDS)).as("owned child process cleanup").isTrue();
    }

    private JsonNode awaitState(String path, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode value;
        do {
            value = request("GET", path, null, null, null, 200).path("data");
            if (value.path("state").asText().equals(expected)) return value;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertThat(value.path("state").asText()).isEqualTo(expected);
        return value;
    }

    private JsonNode request(String method, String path, Object body, String key, String credential, int status) throws Exception {
        HttpResponse<String> response = send(method, path, body, key, credential);
        assertThat(response.statusCode()).as(method + " " + path).isEqualTo(status);
        return response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, Object body, String key, String credential) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Origin", "http://localhost:5173");
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (credential != null) request.header("X-Operation-Credential", credential);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
