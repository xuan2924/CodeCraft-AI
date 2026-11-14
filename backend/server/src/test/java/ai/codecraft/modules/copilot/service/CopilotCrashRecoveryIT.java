package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.testfixture.CopilotRecoveryApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real child JVM death, durable database/files and Docker. The model publisher has no network transport. */
@EnabledIfEnvironmentVariable(named = "COPILOT_RECOVERY_IT_RUN", matches = "disposable")
class CopilotCrashRecoveryIT {
    private final ObjectMapper json = new ObjectMapper();
    private String token = secret();
    private final String csrf = secret();
    private final String owner = UUID.randomUUID().toString();
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
        System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD")));
    private HttpClient http;
    private Process application;
    private Path evidence;
    private String wrapping;
    private int port;
    private int boot;

    @Test
    void originalTasksSurviveApprovalWriteAndExecutionCrashesWithoutReplayingUnknownEffects() throws Exception {
        evidence = Files.createTempDirectory(Path.of("target"), "copilot-recovery-").toAbsolutePath();
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key); wrapping = Base64.getEncoder().encodeToString(key);
        try (var reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            http = client;
            try {
                start("normal"); seedUser();
                String model = checkedModel();
                String problem = request("GET", "/api/problems", null, null, null, 200).get(0).path("problemVersionId").asText();
                String exercise = request("POST", "/api/exercises", Map.of("problemVersionId", problem), "exercise", null, 202).path("exerciseId").asText();
                String base = "/api/exercises/" + exercise;
                await(base, value -> value.path("state").asText().equals("READY"));
                String credential = request("POST", base + "/control/acquire", Map.of(), "control", null, 200).path("credential").asText();
                JsonNode selected = request("GET", "/api/model-configurations/" + model, null, null, null, 200);
                request("POST", base + "/model", Map.of("connectionId", selected.path("connectionId").asText(), "modelConfigurationId", model,
                    "connectionConfigurationVersion", "1", "modelConfigurationVersion", "1"), "select", credential, 200);
                request("POST", base + "/start", Map.of(), "begin", credential, 200);

                String first = startTask(base, credential, "first", "fixture-proposal 0 first");
                awaitTask(first, "WAITING_APPROVAL");
                String proposal = proposal(first);
                JsonNode originalDiff = request("GET", "/api/copilot-proposals/" + proposal, null, null, null, 200).path("files");
                stop(); start("write-before");
                assertThat(request("GET", "/api/copilot-tasks/" + first, null, null, null, 200).path("state").asText()).isEqualTo("WAITING_APPROVAL");
                assertThat(request("GET", "/api/copilot-proposals/" + proposal, null, null, null, 200).path("files")).isEqualTo(originalDiff);
                assertThat(request("GET", "/api/copilot-proposals/" + proposal + "/status", null, null, null, 200).path("state").asText()).isEqualTo("PENDING");
                approve(proposal, credential); awaitCrash("write-before", "1");
                start("recover-blocked");
                awaitTask(first, "WAITING_WRITE");
                JsonNode adjustment = request("POST", "/api/copilot-tasks/" + first + "/steers", Map.of("text", "fixture-finish"), "unknown-write-steer", credential, 202);
                assertThat(adjustment.path("state").asText()).isEqualTo("RECEIVED");
                assertThat(request("GET", "/api/copilot-tasks/" + first, null, null, null, 200).path("state").asText()).isEqualTo("WAITING_WRITE");
                Files.writeString(evidence.resolve("release-write.txt"), "release");
                awaitTask(first, "SUCCEEDED"); assertRevision(base, "1", "first");
                assertThat(request("GET", "/api/copilot-tasks/" + first + "/steers", null, null, null, 200).path("items").get(0).path("state").asText()).isEqualTo("APPLIED");
                assertThat(request("GET", base + "/copilot/messages", null, null, null, 200).toString()).contains("STEER_SUPERSEDED");

                stop(); start("write-after");
                String second = startTask(base, credential, "second", "fixture-proposal 1 second");
                awaitTask(second, "WAITING_APPROVAL"); approve(proposal(second), credential); awaitCrash("write-after", "2");
                assertThat(jdbc.queryForObject("SELECT state FROM workspace_operation WHERE id=?::uuid", String.class,
                    Files.readAllLines(evidence.resolve("write-after.txt")).getFirst())).isEqualTo("SUCCEEDED");
                start("normal"); awaitTask(second, "SUCCEEDED"); assertRevision(base, "2", "second");
                assertThat(Files.exists(evidence.resolve("write-replayed.txt"))).isFalse();
                assertThat(Files.readAllLines(evidence.resolve("applies.txt"))).hasSize(2).doesNotHaveDuplicates();

                String streaming = startTask(base, credential, "stream", "fixture-stream");
                await(base + "/copilot/tasks/latest", value -> value.path("partial").path("text").asText().equals("Incomplete fixture preview"));
                stop();
                // Only an internal worker lease is aged, after the old JVM is confirmed dead. No authorization clock is changed.
                assertThat(jdbc.update("UPDATE copilot_task SET worker_not_after=now()-interval '1 second' WHERE id=?::uuid AND worker_id IS NOT NULL", streaming)).isEqualTo(1);
                start("normal");
                JsonNode interrupted = awaitTask(streaming, "INTERRUPTED"); assertThat(interrupted.path("reason").asText()).isEqualTo("MODEL_RESULT_UNKNOWN");
                assertThat(request("GET", base + "/copilot/tasks/latest", null, null, null, 200).path("partial").path("text").asText()).isEqualTo("Incomplete fixture preview");
                assertThat(Files.readAllLines(evidence.resolve("model-calls.txt")).stream().filter("fixture-stream"::equals).count()).isEqualTo(1);

                String obsolete = credential;
                credential = request("POST", base + "/control/takeover", Map.of(), "takeover", null, 200).path("credential").asText();
                var stale = send("POST", base + "/copilot/tasks", Map.of("text", "fixture-finish", "feeAcknowledged", true), "old-page", obsolete);
                assertThat(stale.statusCode()).isEqualTo(409); assertThat(json.readTree(stale.body()).path("code").asText()).isEqualTo("STALE_CONTROL");
                request("POST", base + "/copilot/approval-mode", Map.of("mode", "AUTO", "expectedVersion", "0"), "auto", credential, 200);
                String automatic = startTask(base, credential, "automatic", "fixture-proposal 2 third");
                // Observation has no page credential, as after a refresh/disconnection; background authorization remains valid.
                awaitTask(automatic, "SUCCEEDED"); assertRevision(base, "3", "third");
                assertThat(request("GET", "/api/copilot-proposals/" + proposal(automatic) + "/status", null, null, null, 200).path("mode").asText()).isEqualTo("AUTO");
                Files.writeString(evidence.resolve("block-auto.txt"), "hold before auto authorization");
                String revoked = startTask(base, credential, "revoked", "fixture-proposal 3 revoked");
                awaitTask(revoked, "WAITING_APPROVAL"); String revokedProposal = proposal(revoked);
                request("POST", "/api/auth/logout", Map.of(), "logout", null, 204);
                assertThat(send("GET", "/api/copilot-tasks/" + revoked, null, null, null).statusCode()).isEqualTo(401);
                token = secret(); seedSession();
                Files.writeString(evidence.resolve("release-auto.txt"), "release after original session revocation");
                awaitTask(revoked, "INTERRUPTED");
                JsonNode rejected = request("GET", "/api/copilot-proposals/" + revokedProposal + "/status", null, null, null, 200);
                assertThat(rejected.path("state").asText()).isEqualTo("CANCELLED"); assertThat(rejected.path("failureCode").asText()).isEqualTo("AUTHORIZATION_ENDED");
                assertThat(request("GET", base, null, null, null, 200).path("revision").asText()).isEqualTo("3");
                assertThat(Files.readAllLines(evidence.resolve("applies.txt"))).hasSize(3);
                credential = request("POST", base + "/control/takeover", Map.of(), "relogin-takeover", null, 200).path("credential").asText();
                String original = request("GET", base + "/files?path=" + CopilotRecoveryApplication.SOURCE, null, null, null, 200).path("content").asText();
                String slow = original.replace("public final class CartTotal {", "public final class CartTotal { static { try { Thread.sleep(60000); } catch (InterruptedException e) { throw new RuntimeException(e); } }");
                assertThat(slow).isNotEqualTo(original);
                request("POST", base + "/file-batches", Map.of("expectedRevision", "3", "changes", List.of(Map.of("kind", "UPDATE", "path", CopilotRecoveryApplication.SOURCE, "content", slow))), "slow", credential, 200);
                stop(); start("test-crash");
                String testing = startTask(base, credential, "test", "fixture-test 4");
                awaitDeath();
                List<String> child = Files.readAllLines(evidence.resolve("test-crash.txt")); assertThat(child).hasSize(2);
                restartGo(); start("test-recover");
                awaitTask(testing, "WAITING_TEST");
                var busy = send("POST", base + "/tests", Map.of("expectedRevision", "4", "target", "base"), "independent", credential);
                assertThat(busy.statusCode()).isEqualTo(409); assertThat(json.readTree(busy.body()).path("code").asText()).isEqualTo("TEST_SLOT_BUSY");
                request("POST", "/api/copilot-tasks/" + testing + "/stop", Map.of(), "stop-parent", null, 200);
                Files.writeString(evidence.resolve("release-test.txt"), "release");
                awaitTask(testing, "STOPPED");
                JsonNode result = await(base + "/tasks/" + child.getFirst(), value -> value.path("quiescent").asBoolean() && value.path("copyCleanup").asText().equals("SUCCEEDED"));
                assertThat(result.path("executionInstanceId").asText()).isEqualTo(child.get(1));
                assertThat(result.path("state").asText()).isIn("STOPPED", "TIMED_OUT");
                assertThat(request("GET", "/api/copilot-tasks/" + testing + "/tests", null, null, null, 200)).hasSize(1);
                assertThat(Files.readAllLines(evidence.resolve("starts.txt"))).containsExactly(child.getFirst());
                assertThat(Files.exists(evidence.resolve("test-replayed.txt"))).isFalse();
                assertThat(Files.readAllLines(evidence.resolve("model-calls.txt")).stream().filter("fixture-test 4"::equals).count()).isEqualTo(1);
                Files.writeString(evidence.resolve("result.json"), json.writeValueAsString(Map.of("parentState", "STOPPED", "childState", result.path("state").asText(),
                    "childTaskId", child.getFirst(), "executionInstanceId", child.get(1), "quiescent", true, "revision", "4", "autoRevocation", "AUTHORIZATION_ENDED")));
                System.out.println("Copilot recovery: approval restart; write before/after Java receipt; steer reconciliation; unknown model not replayed; takeover and auto revocation; original Docker execution after Java/Go restart=" + result.path("state").asText() + ". Evidence=" + evidence);
            } finally { stop(); }
        }
    }

    private static String secret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private void seedUser() {
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES (?::uuid,?,'unused-fixture-hash','USER',false)", owner, "recovery_" + owner.substring(0, 8));
        seedSession();
    }
    private void seedSession() {
        jdbc.update("INSERT INTO identity_session(token_hash,user_id,auth_version,csrf_token,issued_at,last_activity) VALUES (?,?::uuid,1,?,now(),now())", IdentitySecrets.digest(token), owner, csrf);
    }
    private String checkedModel() throws Exception {
        String connection = request("POST", "/api/model-connections", Map.of("name", "No-network fixture", "baseUrl", CopilotRecoveryApplication.BASE,
            "protocol", "OPENAI_CHAT", "preset", "CUSTOM", "apiKey", "fixture-not-a-real-provider-key"), "connection", null, 201).path("id").asText();
        String model = request("POST", "/api/model-connections/" + connection + "/models", Map.of("name", "Recovery fixture", "modelId", "fixture-recovery",
            "contextWindow", 100000, "maxOutputTokens", 2048, "thinkingMode", "OFF"), "model", null, 201).path("id").asText();
        String check = request("POST", "/api/model-configurations/" + model + "/checks", Map.of("feeAcknowledged", true), "check", null, 202).path("id").asText();
        await("/api/model-checks/" + check, value -> {
            String state = value.path("check").path("status").asText();
            if (List.of("QUEUED", "RUNNING").contains(state)) return false;
            assertThat(state).as("Controlled check: %s", value.path("reason").asText()).isEqualTo("SUCCEEDED"); return true;
        });
        return model;
    }
    private String startTask(String base, String credential, String key, String text) throws Exception {
        return request("POST", base + "/copilot/tasks", Map.of("text", text, "feeAcknowledged", true), key, credential, 202).path("id").asText();
    }
    private String proposal(String task) throws Exception {
        return request("GET", "/api/copilot-tasks/" + task + "/proposals", null, null, null, 200).path("items").get(0).path("id").asText();
    }
    private void approve(String proposal, String credential) throws Exception {
        try { request("POST", "/api/copilot-proposals/" + proposal + "/decision", Map.of("decision", "APPROVE"), "approve", credential, 200); }
        catch (IOException lostResponse) { /* Exact fsynced crash marker, not transport failure, proves the boundary. */ }
    }
    private void assertRevision(String base, String revision, String suffix) throws Exception {
        assertThat(request("GET", base, null, null, null, 200).path("revision").asText()).isEqualTo(revision);
        assertThat(request("GET", base + "/files?path=src/main/java/exercise/Recovery_" + suffix + ".java", null, null, null, 200).path("content").asText())
            .isEqualTo("package exercise; final class Recovery_" + suffix + " {}");
    }
    private JsonNode awaitTask(String task, String state) throws Exception {
        return await("/api/copilot-tasks/" + task, value -> {
            String actual = value.path("state").asText();
            if (actual.equals(state)) return true;
            if (List.of("SUCCEEDED", "FAILED", "STOPPED", "INTERRUPTED", "PAUSED").contains(actual)) {
                throw new AssertionError("Expected " + state + " but got " + actual + ": " + value.path("reason").asText());
            }
            return false;
        });
    }
    private JsonNode await(String path, Predicate<JsonNode> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        JsonNode last;
        do {
            last = request("GET", path, null, null, null, 200);
            if (condition.test(last)) return last;
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        throw new AssertionError("Recovery did not converge: " + path + "; state=" + last.path("state") + "; reason=" + last.path("reason"));
    }
    private void awaitDeath() throws Exception {
        assertThat(application.waitFor(30, TimeUnit.SECONDS)).as("precise owned JVM crash barrier").isTrue();
        assertThat(application.exitValue()).isEqualTo(137);
    }
    private void awaitCrash(String point, String revision) throws Exception {
        awaitDeath(); List<String> marker = Files.readAllLines(evidence.resolve(point + ".txt"));
        assertThat(marker).hasSize(2); assertThat(marker.get(1)).isEqualTo(revision);
    }
    private void start(String mode) throws Exception {
        Path log = evidence.resolve(++boot + "-" + mode + ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("surefire.test.class.path"),
            CopilotRecoveryApplication.class.getName(), mode, evidence.toString(), Integer.toString(port));
        builder.environment().put("MODEL_CREDENTIAL_ACTIVE_KEY_ID", "fixture"); builder.environment().put("MODEL_CREDENTIAL_KEYS", "fixture:" + wrapping);
        application = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (application.isAlive() && System.nanoTime() < until) {
            try { if (send("GET", "/api/internal/status", null, null, null).statusCode() == 200) return; }
            catch (IOException notReady) { /* bounded startup readiness, not time-policy validation */ }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Child readiness failed: " + log);
    }
    private void stop() throws Exception {
        if (application == null || !application.isAlive()) return;
        application.destroyForcibly(); assertThat(application.waitFor(10, TimeUnit.SECONDS)).as("owned child cleanup").isTrue();
    }
    private void restartGo() throws Exception {
        var process = new ProcessBuilder("docker", "restart", System.getenv("SANDBOX_TEST_CONTAINER")).redirectErrorStream(true)
            .redirectOutput(evidence.resolve("go-restart.log").toFile()).start();
        try { assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private JsonNode request(String method, String path, Object body, String key, String credential, int status) throws Exception {
        var response = send(method, path, body, key, credential);
        JsonNode parsed = response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
        assertThat(response.statusCode()).as("%s %s (%s)", method, path, parsed.path("code").asText()).isEqualTo(status);
        return parsed.path("data");
    }
    private HttpResponse<String> send(String method, String path, Object body, String key, String credential) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
            .header("Origin", "http://localhost:5173").header("Cookie", IdentityHttp.SESSION_COOKIE + "=" + token)
            .header("X-CSRF-TOKEN", csrf).header("X-Codecraft-Owner", owner);
        if (key != null) request.header("Idempotency-Key", key);
        if (credential != null) request.header("X-Operation-Credential", credential);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
