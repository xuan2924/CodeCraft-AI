package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.service.ModelCheckService;
import ai.codecraft.modules.modelaccess.service.ModelConfigurationService;
import ai.codecraft.modules.modelaccess.service.ModelConnectionService;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Paid opt-in: real model/checks, REST, PostgreSQL, Linux Go and Docker; the spy only bounds test spending. */
@EnabledIfEnvironmentVariable(named = "COPILOT_WORKSPACE_IT_RUN", matches = "disposable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "identity.public-origin=http://localhost:5173")
class CopilotWorkspaceIT {
    private static final String SOURCE = "src/main/java/interview/CartTotal.java";
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("workspaceJson") private ObjectMapper json;
    @Autowired private IdentitySecrets secrets;
    @Autowired private ModelConnectionService connections;
    @Autowired private ModelConfigurationService models;
    @Autowired private ModelCheckService checks;
    @Autowired private ExerciseModelService selection;
    @MockitoSpyBean private ModelCallDriver driver;
    private HttpClient http;
    private String token;
    private String csrf;

    @DynamicPropertySource
    static void ephemeralWrappingKey(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        String encoded = "fixture:" + Base64.getEncoder().encodeToString(key);
        registry.add("model.security.active-key-id", () -> "fixture");
        registry.add("model.security.keys", () -> encoded);
    }

    @Test
    void realModelWaitsForStableDockerEvidenceAndParentStopConfirmsProcessExit() throws Exception {
        String key = System.getenv("DeepSeek");
        assertThat(key != null && !key.isBlank()).as("Explicit live validation requires its environment credential").isTrue();
        token = secrets.token(); csrf = secrets.token();
        String owner = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES (?::uuid,?,'unused-fixture-hash','USER',false)", owner, "copilot_" + owner.substring(0, 8));
        jdbc.update("INSERT INTO identity_session(token_hash,user_id,auth_version,csrf_token,issued_at,last_activity) VALUES (?,?::uuid,1,?,now(),now())", IdentitySecrets.digest(token), owner, csrf);
        String session = IdentitySecrets.digest(token);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() > 9) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
            return call.callRealMethod();
        }).when(driver).call(any(), any(), any(), anyInt(), any(), any());
        var connection = connections.create(session, "live-connection", new ModelConnectionRequest("Live Docker fixture", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, key, null));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            http = client;
            var model = models.create(session, connection.id(), "live-model", new ModelConfigurationRequest("Live Docker fixture", "deepseek-flash", 100000, 1024, ModelThinkingMode.OFF, null, null, null, null));
            var check = checks.start(session, model.id(), "check", true);
            long checkUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (true) {
                var status = checks.status(session, check.id());
                if (status.check().status().equals("SUCCEEDED")) break;
                assertThat(List.of("QUEUED", "RUNNING")).as("Model check state/reason: %s / %s", status.check().status(), status.reason()).contains(status.check().status());
                if (System.nanoTime() >= checkUntil) throw new AssertionError("Model check did not converge within the live probe budget");
                Thread.sleep(200);
            }
            String problem = request("GET", "/api/problems", null, null, null, 200).get(0).path("problemVersionId").asText();
            String exercise = request("POST", "/api/exercises", Map.of("problemVersionId", problem), "exercise", null, 202).path("exerciseId").asText();
            String base = "/api/exercises/" + exercise;
            await(base, value -> value.path("state").asText().equals("READY"));
            String credential = request("POST", base + "/control/acquire", Map.of(), "control", null, 200).path("credential").asText();
            selection.select(session, exercise, credential, "select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
            request("POST", base + "/start", Map.of(), "begin", credential, 200);
            String code = request("GET", base + "/files?path=" + SOURCE, null, null, null, 200).path("content").asText();
            String old = code.replace("public final class CartTotal {", "public final class CartTotal { static { System.out.println(\"OLD_TEST_INPUT\"); try { Thread.sleep(2000); } catch (InterruptedException e) { throw new RuntimeException(e); } }");
            save(base, credential, "0", old, "old-input");
            String first = start(base, credential, "first", "1");
            JsonNode binding = await("/api/copilot-tasks/" + first + "/tests", value -> value.size() == 1).get(0);
            String child = base + "/tasks/" + binding.path("testTaskId").asText();
            await(child, value -> value.path("state").asText().equals("RUNNING"));
            save(base, credential, "1", code.replace("public final class CartTotal {", "public final class CartTotal { static { System.out.println(\"NEW_TEST_INPUT\"); }"), "new-input");
            JsonNode result = await(child, value -> value.path("copyCleanup").asText().equals("SUCCEEDED"));
            assertThat(result.path("state").asText()).isEqualTo("FAILED");
            assertThat(result.path("revision").asText()).isEqualTo("1");
            assertThat(result.path("logs").toString()).contains("OLD_TEST_INPUT").doesNotContain("NEW_TEST_INPUT");
            assertThat(result.path("quiescent").asBoolean()).isTrue();
            assertThat(result.path("cleanup").asText()).isEqualTo("SUCCEEDED");
            assertThat(result.path("evidence").path("kind").asText()).isEqualTo("SUREFIRE_XML");
            JsonNode parent = await("/api/copilot-tasks/" + first, value -> terminal(value.path("state").asText()));
            assertThat(parent.path("state").asText()).as("Copilot outcome reason: %s", parent.path("reason").asText()).isEqualTo("SUCCEEDED");
            var messages = request("GET", base + "/copilot/messages?after=-1", null, null, null, 200).path("messages");
            assertThat(messages.get(messages.size() - 1).path("text").asText().toUpperCase()).contains("FAILED");
            assertThat(request("GET", "/api/copilot-tasks/" + first + "/tests", null, null, null, 200)).hasSize(1);
            assertThat(request("GET", base, null, null, null, 200).path("revision").asText()).isEqualTo("2");

            String slow = code.replace("public final class CartTotal {", "public final class CartTotal { static { try { Thread.sleep(60000); } catch (InterruptedException e) { throw new RuntimeException(e); } }");
            save(base, credential, "2", slow, "slow-input");
            String second = start(base, credential, "second", "3");
            JsonNode secondBinding = await("/api/copilot-tasks/" + second + "/tests", value -> value.size() == 1).get(0);
            String secondChild = base + "/tasks/" + secondBinding.path("testTaskId").asText();
            await(secondChild, value -> value.path("state").asText().equals("RUNNING"));
            assertThat(request("POST", "/api/copilot-tasks/" + second + "/stop", Map.of(), "stop", null, 200).path("state").asText()).isEqualTo("STOPPING");
            JsonNode stopped = await("/api/copilot-tasks/" + second, value -> terminal(value.path("state").asText()));
            assertThat(stopped.path("state").asText()).isEqualTo("STOPPED");
            JsonNode stoppedTest = await(secondChild, value -> value.path("copyCleanup").asText().equals("SUCCEEDED"));
            assertThat(stoppedTest.path("quiescent").asBoolean()).isTrue();
            assertThat(stoppedTest.path("state").asText()).isEqualTo("STOPPED");
            assertThat(stoppedTest.path("cleanup").asText()).isEqualTo("SUCCEEDED");
            assertThat(request("GET", base + "/files?path=" + SOURCE, null, null, null, 200).path("content").asText()).isEqualTo(slow);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_turn WHERE task_id=?::uuid", Integer.class, second)).isEqualTo(1);
            assertThat(calls.get()).isBetween(6, 9);
            System.out.println("Live Copilot Docker probe: stable revision 1 while saved revision became 2; FAILED evidence consumed; second test STOPPED and cleaned. Paid calls=" + calls.get());
            System.out.println("Copilot usage only: " + jdbc.queryForList("SELECT input_tokens,output_tokens,total_tokens FROM copilot_turn WHERE exercise_id=?::uuid ORDER BY created_at", exercise));
        } finally { connections.delete(session, connection.id(), "cleanup", connection.version()); }
    }

    private String start(String base, String credential, String key, String revision) throws Exception {
        return request("POST", base + "/copilot/tasks", Map.of("feeAcknowledged", true, "text",
            "Use run_tests exactly once with expected_revision \"" + revision + "\" and target \"base\". Do not use any other tool. After its confirmed result, reply briefly with the exact result state (including FAILED if tests fail). Never start another test."), key, credential, 202).path("id").asText();
    }

    private void save(String base, String credential, String revision, String code, String key) throws Exception {
        request("POST", base + "/file-batches", Map.of("expectedRevision", revision, "changes", List.of(Map.of("path", SOURCE, "kind", "UPDATE", "content", code))), key, credential, 200);
    }

    private boolean terminal(String state) { return List.of("STOPPED", "SUCCEEDED", "FAILED", "INTERRUPTED", "PAUSED").contains(state); }

    private JsonNode await(String path, Predicate<JsonNode> predicate) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            JsonNode value = request("GET", path, null, null, null, 200);
            if (predicate.test(value)) return value;
            if (System.nanoTime() >= until) throw new AssertionError("Live probe did not converge: " + path + "; state=" + value.path("state").asText());
            Thread.sleep(200);
        }
    }

    private JsonNode request(String method, String path, Object body, String key, String credential, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
            .header("Cookie", "codecraft_session=" + token).header("Origin", "http://localhost:5173").header("X-CSRF-TOKEN", csrf);
        if (key != null) builder.header("Idempotency-Key", key);
        if (credential != null) builder.header("X-Operation-Credential", credential);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        var envelope = json.readTree(response.body());
        assertThat(response.statusCode()).as("%s %s code=%s", method, path, envelope.path("code").asText()).isEqualTo(expected);
        return envelope.path("data");
    }
}
