package ai.codecraft.modules.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.workspace.config.WorkspaceProperties;
import ai.codecraft.modules.workspace.service.ProblemService;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.FileChange;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

// Real REST + disposable PostgreSQL + authenticated Linux Go service. No mocked RPC/storage.
@EnabledIfEnvironmentVariable(named = "WORKSPACE_IT_RUN", matches = "disposable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "identity.public-origin=http://localhost:5173")
class WorkspaceIT {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Autowired private IdentitySecrets secrets;
    @Autowired private ProblemService publications;
    @Autowired private WorkspaceProperties workspaceProperties;
    @Autowired private SandboxWorkspaceGateway sandboxGateway;
    private String token;
    private String csrf;

    @BeforeEach
    void seedDisposableIdentity() {
        token = secrets.token();
        csrf = secrets.token();
        String user = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity_user(id, username, password_hash, role, password_change_required) VALUES (?::uuid, ?, 'unused-fixture-hash', 'USER', false)",
            user, "ws_" + user.substring(0, 8));
        jdbc.update("INSERT INTO identity_session(token_hash, user_id, auth_version, csrf_token, issued_at, last_activity) VALUES (?, ?::uuid, 1, ?, now(), now())",
            IdentitySecrets.digest(token), user, csrf);
    }

    @AfterEach
    void closeClient() { http.close(); }

    @Test
    void startsOneStableTestWithDurableResultsAndOwnership() throws Exception {
        String exercise = readyExercise("test-task");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "page", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "begin", 200, credential);
        Object input = Map.of("expectedRevision", "0", "target", "base");
        request("POST", base + "/tests", Map.of("expectedRevision", "0", "target", "shell"), "invalid", 400, credential);
        request("POST", base + "/tests", Map.of("expectedRevision", "1", "target", "base"), "stale", 409, credential);
        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> {
            try { return request("POST", base + "/tests", input, "same-key", 202, credential).path("data"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        JsonNode accepted = request("POST", base + "/tests", input, "same-key", 202, credential).path("data");
        assertThat(first.get(10, TimeUnit.SECONDS).path("taskId")).isEqualTo(accepted.path("taskId"));
        assertThat(request("POST", base + "/tests", input, "different-key", 409, credential).path("code").asText()).isEqualTo("TEST_SLOT_BUSY");
        assertThat(request("POST", base + "/tests", Map.of("expectedRevision", "1", "target", "base"), "same-key", 409, credential).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        String source = "src/main/java/interview/CartTotal.java";
        String code = request("GET", base + "/files?path=" + source, null, null, 200).path("data").path("content").asText();
        // REST checks result identity; the Go integration test uses an explicit
        // candidate-code barrier to prove concurrent OLD/NEW input isolation.
        String taskUrl = base + "/tasks/" + accepted.path("taskId").asText();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        JsonNode result;
        do {
            result = request("GET", taskUrl, null, null, 200).path("data");
            if (System.nanoTime() >= until) throw new AssertionError("Task did not register: " + result);
            Thread.sleep(100);
        } while (result.path("state").asText().equals("ACCEPTED"));
        assertThat(request("POST", base + "/file-batches", Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", source, "kind", "UPDATE", "content", code.replace("public final class CartTotal {", "public final class CartTotal { static { System.out.println(\"NEW_VERSION_MARKER\"); }" )))), "save-running", 200, credential).path("data").path("revision").asText()).isEqualTo("1");
        String newCredential = request("POST", base + "/control/takeover", Map.of(), "takeover-test", 200).path("data").path("credential").asText();
        request("POST", base + "/tests", input, "old-page", 409, credential);
        do {
            result = request("GET", taskUrl, null, null, 200).path("data");
            if (System.nanoTime() >= until) throw new AssertionError("Task did not finish: " + result);
            Thread.sleep(100);
        } while (!result.path("copyCleanup").asText().equals("SUCCEEDED"));
        assertThat(result.path("state").asText()).isEqualTo("FAILED");
        assertThat(result.path("logs").toString()).doesNotContain("NEW_VERSION_MARKER");
        assertThat(result.path("revision").asText()).isEqualTo("0");
        assertThat(result.path("exitCode").asInt()).isEqualTo(1);
        assertThat(result.path("problem")).isEqualTo(accepted.path("problem"));
        assertThat(result.path("problem").path("version").asText()).isEqualTo("1.0.1");
        assertThat(result.path("evidence").path("kind").asText()).isEqualTo("SUREFIRE_XML");
        assertThat(result.path("evidence").path("sha256").asText()).matches("[a-f0-9]{64}");
        assertThat(result.path("evidence").path("bytes").asLong()).isBetween(1L, 2097152L);
        assertThat(result.path("quiescent").asBoolean()).isTrue();
        assertThat(result.path("cleanup").asText()).isEqualTo("SUCCEEDED");
        assertThat(request("POST", base + "/tests", input, "same-key", 202, newCredential).path("data").path("taskId")).isEqualTo(accepted.path("taskId"));
        assertThat(request("GET", base + "/tasks", null, null, 200).path("data").path("tasks")).hasSize(1);
        seedDisposableIdentity();
        request("GET", taskUrl, null, null, 404);
        request("GET", base + "/tasks", null, null, 404);
        request("POST", base + "/tests", input, "foreign", 404, newCredential);
    }

    @Test
    void stopsRealExecutionWithCurrentControlAndDurableOutcome() throws Exception {
        String exercise = readyExercise("stop-task");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "page", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "begin", 200, credential);
        String path = "src/main/java/interview/CartTotal.java";
        String code = request("GET", base + "/files?path=" + path, null, null, 200).path("data").path("content").asText();
        code = code.replace("public final class CartTotal {", "public final class CartTotal { static { try { Thread.sleep(60000); } catch (InterruptedException e) { throw new RuntimeException(e); } }");
        request("POST", base + "/file-batches", Map.of("expectedRevision", "0", "changes", List.of(Map.of("path", path, "kind", "UPDATE", "content", code))), "slow", 200, credential);
        JsonNode accepted = request("POST", base + "/tests", Map.of("expectedRevision", "1", "target", "base"), "run", 202, credential).path("data");
        String taskUrl = base + "/tasks/" + accepted.path("taskId").asText();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        JsonNode result;
        do {
            result = request("GET", taskUrl, null, null, 200).path("data");
            if (System.nanoTime() >= until) throw new AssertionError("Task did not run: " + result);
            Thread.sleep(100);
        } while (!result.path("state").asText().equals("RUNNING"));
        String current = request("POST", base + "/control/takeover", Map.of(), "takeover", 200).path("data").path("credential").asText();
        request("POST", taskUrl + "/stop", Map.of(), "old-stop", 409, credential);
        assertThat(request("POST", taskUrl + "/stop", Map.of(), "stop", 202, current).path("data").path("state").asText()).isEqualTo("STOPPING");
        do {
            result = request("GET", taskUrl, null, null, 200).path("data");
            if (System.nanoTime() >= until) throw new AssertionError("Task did not stop: " + result);
            Thread.sleep(100);
        } while (!result.path("copyCleanup").asText().equals("SUCCEEDED"));
        assertThat(result.path("state").asText()).isEqualTo("STOPPED");
        assertThat(result.path("quiescent").asBoolean()).isTrue();
        assertThat(result.path("cleanup").asText()).isEqualTo("SUCCEEDED");
        assertThat(request("POST", taskUrl + "/stop", Map.of(), "stop", 200, current).path("data").path("state").asText()).isEqualTo("STOPPED");
        seedDisposableIdentity();
        request("POST", taskUrl + "/stop", Map.of(), "foreign-stop", 404, current);
    }

    @Test
    void streamsRealLogsAndClosesExistingConnectionOnPasswordReset() throws Exception {
        String exercise = readyExercise("stream-revoke");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "page", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "begin", 200, credential);
        String path = "src/main/java/interview/CartTotal.java";
        String code = request("GET", base + "/files?path=" + path, null, null, 200).path("data").path("content").asText();
        code = code.replace("public final class CartTotal {", "public final class CartTotal { static { System.out.println(\"private-stream-marker\"); System.err.println(\"private-stderr-marker\"); try { Thread.sleep(3000); } catch (InterruptedException e) { throw new RuntimeException(e); } }");
        request("POST", base + "/file-batches", Map.of("expectedRevision", "0", "changes", List.of(Map.of("path", path, "kind", "UPDATE", "content", code))), "logging", 200, credential);
        JsonNode task = request("POST", base + "/tests", Map.of("expectedRevision", "1", "target", "base"), "run", 202, credential).path("data");
        String taskUrl = base + "/tasks/" + task.path("taskId").asText();
        String victimToken = token;
        String victim = jdbc.queryForObject("SELECT user_id::text FROM identity_session WHERE token_hash = ?", String.class, IdentitySecrets.digest(token));
        HttpRequest stream = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + taskUrl + "/events"))
            .header("Cookie", "codecraft_session=" + token).header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> response = http.send(stream, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        CountDownLatch logged = new CountDownLatch(1);
        try (var body = response.body()) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                StringBuilder text = new StringBuilder();
                try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null;) {
                        text.append(line).append('\n');
                        if (line.contains("private-stderr-marker")) logged.countDown();
                        if (text.length() > 131072) throw new IllegalStateException("unbounded test stream");
                    }
                    return text.toString();
                } catch (IOException error) { throw new IllegalStateException(error); }
            });
            assertThat(logged.await(35, TimeUnit.SECONDS)).isTrue();
            assertThat(received.isDone()).isFalse();
            seedDisposableIdentity();
            jdbc.update("UPDATE identity_user SET role = 'ADMIN' WHERE id = (SELECT user_id FROM identity_session WHERE token_hash = ?)", IdentitySecrets.digest(token));
            request("POST", "/api/admin/users/" + victim + "/password-reset", Map.of(), "reset-stream", 200);
            assertThat(received.get(7, TimeUnit.SECONDS)).contains("event:log", "private-stream-marker", "stdout", "stderr");
            token = victimToken;
            request("GET", taskUrl, null, null, 401);
        }
    }

    @Test
    void boundsStreamsAndRechecksRevocationWhenNotificationIsLost() throws Exception {
        String exercise = readyExercise("stream-capacity");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "page", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "begin", 200, credential);
        JsonNode task = request("POST", base + "/tests", Map.of("expectedRevision", "0", "target", "base"), "run", 202, credential).path("data");
        String url = base + "/tasks/" + task.path("taskId").asText();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        do {
            task = request("GET", url, null, null, 200).path("data");
            if (System.nanoTime() > until) throw new AssertionError("Task did not finish");
            Thread.sleep(100);
        } while (!task.path("copyCleanup").asText().equals("SUCCEEDED"));
        assertThat(task.path("cursor").asText()).isNotBlank();
        request("GET", url + "/events?after=invalid", null, null, 409);
        List<InputStream> bodies = new ArrayList<>();
        try {
            for (int index = 0; index < 5; index++) {
                HttpRequest stream = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + url + "/events?after=" + task.path("cursor").asText()))
                    .header("Cookie", "codecraft_session=" + token).GET().build();
                HttpResponse<InputStream> response = http.send(stream, HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);
                bodies.add(response.body());
            }
            request("GET", url + "/events?after=" + task.path("cursor").asText(), null, null, 429);
            // Fault injection: commit the authoritative session revocation without a
            // process-local notification. Assertions remain on the public stream.
            jdbc.update("UPDATE identity_session SET revoked = true WHERE token_hash = ?", IdentitySecrets.digest(token));
            List<CompletableFuture<byte[]>> tails = bodies.stream().map(body -> CompletableFuture.supplyAsync(() -> {
                try { return body.readNBytes(131072); }
                catch (IOException error) { throw new IllegalStateException(error); }
            })).toList();
            for (CompletableFuture<byte[]> tail : tails) assertThat(new String(tail.get(7, TimeUnit.SECONDS), StandardCharsets.UTF_8)).doesNotContain("data:");
            request("GET", url, null, null, 401);
        } finally { for (var body : bodies) body.close(); }
    }

    @Test
    void rejectsUnconfiguredAndExpiredTestsBeforeReservingCapacity() throws Exception {
        String exercise = readyExercise("test-policy");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "page", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "begin", 200, credential);
        String problem = jdbc.queryForObject("SELECT problem_version_id::text FROM workspace_exercise WHERE id = ?::uuid", String.class, exercise);
        String artifact = jdbc.queryForObject("SELECT artifact::text FROM problem_version WHERE id = ?::uuid", String.class, problem);
        try {
            // Isolated deployment-fault fixture, not mutation of a published file/image.
            jdbc.update("UPDATE problem_version SET artifact = artifact #- '{manifest,execution}' WHERE id = ?::uuid", problem);
            assertThat(request("POST", base + "/tests", Map.of("expectedRevision", "0", "target", "base"), "unconfigured", 409, credential)
                .path("code").asText()).isEqualTo("TEST_TARGET_UNAVAILABLE");
        } finally {
            jdbc.update("UPDATE problem_version SET artifact = ?::jsonb WHERE id = ?::uuid", artifact, problem);
        }
        jdbc.update("UPDATE workspace_exercise SET deadline_at = now() - interval '1 second' WHERE id = ?::uuid", exercise);
        request("POST", base + "/tests", Map.of("expectedRevision", "0", "target", "base"), "expired", 409, credential);
        assertThat(request("GET", base + "/tasks", null, null, 200).path("data").path("tasks")).isEmpty();
    }

    @Test
    void savesAtomicBatchesWithControlAndPersistentReceipts() throws Exception {
        String exercise = readyExercise("write-fixture");
        String base = "/api/exercises/" + exercise;
        JsonNode acquired = request("POST", base + "/control/acquire", Map.of(), "page-a", 200).path("data");
        String credential = acquired.path("credential").asText();
        assertThat(credential).hasSize(43);
        assertThat(request("POST", base + "/control/acquire", Map.of(), "page-a", 200).path("data").path("credentialDelivered").asBoolean()).isFalse();
        request("POST", base + "/control/acquire", Map.of(), "page-b", 409);
        Object batch = Map.of("expectedRevision", "0", "changes", List.of(Map.of("path", "src/test/java/Example.java", "kind", "CREATE", "content", "class Example {}")));
        request("POST", base + "/file-batches", batch, "save-before-start", 409, credential);
        request("POST", base + "/start", Map.of(), "start-write", 200, credential);
        JsonNode saved = request("POST", base + "/file-batches", batch, "save-once", 200, credential).path("data");
        assertThat(saved.path("revision").asText()).isEqualTo("1");
        assertThat(saved.path("changed").asBoolean()).isTrue();
        assertThat(request("POST", base + "/file-batches", batch, "save-once", 200, credential).path("data").path("operationId")).isEqualTo(saved.path("operationId"));
        assertThat(request("GET", saved.path("queryUrl").asText(), null, null, 200).path("data").path("revision").asText()).isEqualTo("1");
        Object noChange = Map.of("expectedRevision", "1", "changes", List.of(Map.of("path", "src/test/java/Example.java", "kind", "UPDATE", "content", "class Example {}")));
        JsonNode unchanged = request("POST", base + "/file-batches", noChange, "same-content", 200, credential).path("data");
        assertThat(unchanged.path("changed").asBoolean()).isFalse();
        assertThat(unchanged.path("revision").asText()).isEqualTo("1");
        Object stale = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", "src/test/java/Example.java", "kind", "UPDATE", "content", "stale overwrite")));
        assertThat(request("POST", base + "/file-batches", stale, "stale-baseline", 409, credential)
            .path("code").asText()).isEqualTo("REVISION_CONFLICT");
        JsonNode afterConflict = request("GET", base + "/files?path=src/test/java/Example.java", null, null, 200).path("data");
        assertThat(afterConflict.path("content").asText()).isEqualTo("class Example {}");
        assertThat(afterConflict.path("revision").asText()).isEqualTo("1");
        String newCredential = request("POST", base + "/control/takeover", Map.of(), "takeover", 200).path("data").path("credential").asText();
        request("POST", base + "/file-batches", noChange, "old-page", 409, credential);
        Object rename = Map.of("expectedRevision", "1", "changes", List.of(
            Map.of("path", "src/test/java/Example.java", "kind", "DELETE", "content", ""),
            Map.of("path", "src/test/java/Renamed.java", "kind", "CREATE", "content", "class Renamed {}")));
        assertThat(request("POST", base + "/file-batches", rename, "rename", 200, newCredential).path("data").path("revision").asText()).isEqualTo("2");
        request("GET", base + "/files?path=src/test/java/Example.java", null, null, 404);
        assertThat(request("GET", base + "/files?path=src/test/java/Renamed.java", null, null, 200).path("data").path("content").asText()).isEqualTo("class Renamed {}");
        JsonNode protectedFile = request("GET", base + "/files?path=pom.xml", null, null, 200).path("data");
        assertThat(request("POST", base + "/file-batches", Map.of("expectedRevision", "2", "changes", List.of(Map.of("path", "pom.xml", "kind", "UPDATE", "content", "override"))), "protected", 403, newCredential)
            .path("code").asText()).isEqualTo("PATH_FORBIDDEN");
        assertThat(request("GET", base + "/files?path=pom.xml", null, null, 200).path("data")).isEqualTo(protectedFile);
        assertThat(request("GET", base, null, null, 200).path("data").path("revision").asText()).isEqualTo("2");
        seedDisposableIdentity();
        request("POST", base + "/control/takeover", Map.of(), "foreign-control", 404);
        request("POST", base + "/file-batches", noChange, "foreign-write", 404, newCredential);
        request("GET", saved.path("queryUrl").asText(), null, null, 404);
    }

    @Test
    void unsafePathsAndBinaryContentCannotChangeWorkspaceFiles() throws Exception {
        String exercise = readyExercise("invalid-file-batches");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "control", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "start", 200, credential);
        String path = "src/main/java/interview/CartTotal.java";
        JsonNode original = request("GET", base + "/files?path=" + path, null, null, 200).path("data");
        for (String invalidPath : List.of("../outside.java", "src/main/java/../outside.java", "/src/main/java/Absolute.java", "src\\main\\java\\Escape.java")) {
            Object batch = Map.of("expectedRevision", "0", "changes", List.of(
                Map.of("path", invalidPath, "kind", "CREATE", "content", "must not be published")));
            assertThat(request("POST", base + "/file-batches", batch, UUID.randomUUID().toString(), 400, credential)
                .path("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        Object binary = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", path, "kind", "UPDATE", "content", "binary\u0000content")));
        assertThat(request("POST", base + "/file-batches", binary, "binary-content", 400, credential)
            .path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(request("GET", base + "/files?path=" + path, null, null, 200).path("data")).isEqualTo(original);
        assertThat(request("GET", base, null, null, 200).path("data").path("revision").asText()).isEqualTo("0");
    }

    @Test
    void failedAndStaleRenameBatchesLeaveTheOriginalTreeIntact() throws Exception {
        String exercise = readyExercise("atomic-file-conflicts");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "control", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "start", 200, credential);
        String source = "src/main/java/interview/CartTotal.java";
        String target = "src/main/java/interview/Renamed.java";
        String missing = "src/test/java/Missing.java";
        JsonNode original = request("GET", base + "/files?path=" + source, null, null, 200).path("data");
        String content = original.path("content").asText();
        Object duplicate = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", source, "kind", "CREATE", "content", "must not replace source")));
        assertThat(request("POST", base + "/file-batches", duplicate, "duplicate-file", 409, credential)
            .path("code").asText()).isEqualTo("FILE_EXISTS");
        Object absent = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", missing, "kind", "DELETE", "content", "")));
        assertThat(request("POST", base + "/file-batches", absent, "missing-file", 404, credential)
            .path("code").asText()).isEqualTo("FILE_NOT_FOUND");
        Object partial = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", source, "kind", "DELETE", "content", ""),
            Map.of("path", target, "kind", "CREATE", "content", content),
            Map.of("path", missing, "kind", "DELETE", "content", "")));
        assertThat(request("POST", base + "/file-batches", partial, "failed-rename", 404, credential)
            .path("code").asText()).isEqualTo("FILE_NOT_FOUND");
        assertThat(request("GET", base + "/files?path=" + source, null, null, 200).path("data")).isEqualTo(original);
        request("GET", base + "/files?path=" + target, null, null, 404);
        assertThat(request("GET", base, null, null, 200).path("data").path("revision").asText()).isEqualTo("0");
        Object advance = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", source, "kind", "UPDATE", "content", content + "\n// newer version\n")));
        assertThat(request("POST", base + "/file-batches", advance, "advance-baseline", 200, credential)
            .path("data").path("revision").asText()).isEqualTo("1");
        Object stale = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", source, "kind", "DELETE", "content", ""),
            Map.of("path", target, "kind", "CREATE", "content", content)));
        assertThat(request("POST", base + "/file-batches", stale, "stale-rename", 409, credential)
            .path("code").asText()).isEqualTo("REVISION_CONFLICT");
        JsonNode preserved = request("GET", base + "/files?path=" + source, null, null, 200).path("data");
        assertThat(preserved.path("content").asText()).isEqualTo(content + "\n// newer version\n");
        assertThat(preserved.path("revision").asText()).isEqualTo("1");
        request("GET", base + "/files?path=" + target, null, null, 404);
        assertThat(request("GET", base, null, null, 200).path("data").path("revision").asText()).isEqualTo("1");
    }

    @Test
    void concurrentSavesFromOneBaselinePublishExactlyOneVersion() throws Exception {
        String exercise = readyExercise("concurrent-save");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "control", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "start", 200, credential);
        String path = "src/main/java/interview/CartTotal.java";
        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (String content : List.of("first candidate", "second candidate")) {
            Object batch = Map.of("expectedRevision", "0", "changes", List.of(
                Map.of("path", path, "kind", "UPDATE", "content", content)));
            HttpRequest write = buildRequest("POST", base + "/file-batches", batch, UUID.randomUUID().toString(), credential);
            pending.add(http.sendAsync(write, HttpResponse.BodyHandlers.ofString()));
        }
        HttpResponse<String> first = pending.get(0).get(15, TimeUnit.SECONDS);
        HttpResponse<String> second = pending.get(1).get(15, TimeUnit.SECONDS);
        assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(200, 409);
        JsonNode failure = json.readTree(first.statusCode() == 409 ? first.body() : second.body());
        assertThat(failure.path("code").asText()).isEqualTo("REVISION_CONFLICT");
        JsonNode winner = json.readTree(first.statusCode() == 200 ? first.body() : second.body()).path("data");
        assertThat(winner.path("revision").asText()).isEqualTo("1");
        JsonNode file = request("GET", base + "/files?path=" + path, null, null, 200).path("data");
        assertThat(file.path("content").asText()).isEqualTo(first.statusCode() == 200 ? "first candidate" : "second candidate");
        assertThat(file.path("revision").asText()).isEqualTo("1");
        assertThat(request("GET", base, null, null, 200).path("data").path("revision").asText()).isEqualTo("1");
        JsonNode receipt = request("GET", winner.path("queryUrl").asText(), null, null, 200).path("data");
        assertThat(receipt.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(receipt.path("revision").asText()).isEqualTo("1");
    }

    @Test
    void expiredExerciseRejectsNewWritesWithoutLosingCommittedReceipts() throws Exception {
        String exercise = readyExercise("expired-save");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "control", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "start", 200, credential);
        String path = "src/test/java/Deadline.java";
        Object initial = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", path, "kind", "CREATE", "content", "class Deadline {}")));
        JsonNode saved = request("POST", base + "/file-batches", initial, "before-deadline", 200, credential).path("data");
        assertThat(saved.path("revision").asText()).isEqualTo("1");
        // Fixture only: normal expiry in the shared Linux clock domain, no clock adjustment.
        jdbc.update("UPDATE workspace_exercise SET deadline_at = now() - interval '1 second' WHERE id = ?::uuid", exercise);
        Object update = Map.of("expectedRevision", "1", "changes", List.of(
            Map.of("path", path, "kind", "UPDATE", "content", "must not be published")));
        assertThat(request("POST", base + "/file-batches", update, "after-deadline", 409, credential)
            .path("code").asText()).isEqualTo("EXERCISE_EXPIRED");
        JsonNode file = request("GET", base + "/files?path=" + path, null, null, 200).path("data");
        assertThat(file.path("content").asText()).isEqualTo("class Deadline {}");
        assertThat(file.path("revision").asText()).isEqualTo("1");
        assertThat(request("GET", base, null, null, 200).path("data").path("revision").asText()).isEqualTo("1");
        JsonNode receipt = request("GET", saved.path("queryUrl").asText(), null, null, 200).path("data");
        assertThat(receipt.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(receipt.path("revision").asText()).isEqualTo("1");
        assertThat(request("POST", base + "/file-batches", initial, "before-deadline", 200, credential)
            .path("data").path("operationId")).isEqualTo(saved.path("operationId"));
    }

    @Test
    void unstartedAndReadOnlyExercisesRejectWritesWithoutChangingTheWorkspace() throws Exception {
        String exercise = readyExercise("read-only-save");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "control", 200).path("data").path("credential").asText();
        String path = "src/main/java/interview/CartTotal.java";
        JsonNode original = request("GET", base + "/files?path=" + path, null, null, 200).path("data");
        Object update = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", path, "kind", "UPDATE", "content", "must not be published")));
        assertThat(request("POST", base + "/file-batches", update, "before-start", 409, credential)
            .path("code").asText()).isEqualTo("INVALID_STATE");
        assertThat(request("GET", base + "/files?path=" + path, null, null, 200).path("data")).isEqualTo(original);
        request("POST", base + "/start", Map.of(), "start", 200, credential);
        // State fixtures test admission only. Closing/freezing transitions belong to ticket #15.
        for (String state : List.of("CLOSING", "FINAL_TESTING", "ENDED")) {
            jdbc.update("UPDATE workspace_exercise SET state = ? WHERE id = ?::uuid", state, exercise);
            assertThat(request("GET", base, null, null, 200).path("data").path("state").asText()).isEqualTo(state);
            assertThat(request("POST", base + "/file-batches", update, "read-only-" + state, 409, credential)
                .path("code").asText()).isEqualTo("INVALID_STATE");
            assertThat(request("GET", base + "/files?path=" + path, null, null, 200).path("data")).isEqualTo(original);
            assertThat(request("GET", base, null, null, 200).path("data").path("revision").asText()).isEqualTo("0");
        }
    }

    @Test
    void takeoverWaitsForUnavailableSandboxAndReconcilesTheOriginalWrite() throws Exception {
        String exercise = readyExercise("takeover-pending-write");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "control", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "start", 200, credential);
        Object batch = Map.of("expectedRevision", "0", "changes", List.of(
            Map.of("path", "src/test/java/Pending.java", "kind", "CREATE", "content", "class Pending {}")));
        String container = System.getenv("SANDBOX_TEST_CONTAINER");
        assertThat(container).matches("[0-9a-f]{64}");
        JsonNode accepted;
        docker("pause", container);
        try {
            accepted = request("POST", base + "/file-batches", batch, "pending-write", 202, credential).path("data");
            JsonNode takeover = request("POST", base + "/control/takeover", Map.of(), "takeover", 202).path("data");
            assertThat(takeover.path("state").asText()).isEqualTo("PENDING");
            assertThat(takeover.path("credentialDelivered").asBoolean()).isFalse();
            assertThat(takeover.path("credential").isNull()).isTrue();
            request("POST", base + "/file-batches", batch, "during-transition", 409, credential);
        } finally { docker("unpause", container); }
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode control;
        do {
            control = request("GET", base + "/control", null, null, 200).path("data");
            if (control.path("state").asText().equals("SUCCEEDED")) break;
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        assertThat(control.path("state").asText()).isEqualTo("SUCCEEDED");
        JsonNode result = request("GET", accepted.path("queryUrl").asText(), null, null, 200).path("data");
        // A committed write wins before CloseGate; otherwise the closed gate proves rejection.
        // Neither outcome permits a pending receipt or partial/new effect after takeover.
        assertThat(result.path("state").asText()).isIn("SUCCEEDED", "REJECTED");
        String revision = result.path("state").asText().equals("SUCCEEDED") ? "1" : "0";
        assertThat(request("GET", base, null, null, 200).path("data").path("revision").asText()).isEqualTo(revision);
        if (revision.equals("1")) {
            assertThat(request("GET", base + "/files?path=src/test/java/Pending.java", null, null, 200)
                .path("data").path("content").asText()).isEqualTo("class Pending {}");
        } else {
            request("GET", base + "/files?path=src/test/java/Pending.java", null, null, 404);
        }
        request("POST", base + "/file-batches", batch, "late-old-page", 409, credential);
        String replacement = request("POST", base + "/control/takeover", Map.of(), "explicit-new-secret", 200)
            .path("data").path("credential").asText();
        Object fresh = Map.of("expectedRevision", revision, "changes", List.of(
            Map.of("path", "src/test/java/NewPage.java", "kind", "CREATE", "content", "class NewPage {}")));
        assertThat(request("POST", base + "/file-batches", fresh, "new-page", 200, replacement)
            .path("data").path("revision").asText()).isEqualTo(revision.equals("1") ? "2" : "1");
    }

    @Test
    void logoutFencesWritableWorkspaceAndSamePageCanResumeAfterRelogin() throws Exception {
        String exercise = readyExercise("logout-writable");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "control", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "start", 200, credential);
        String owner = jdbc.queryForObject("SELECT user_id::text FROM identity_session WHERE token_hash = ?", String.class, IdentitySecrets.digest(token));
        Object batch = Map.of("expectedRevision", "0", "changes", List.of(Map.of("path", "src/test/java/Resume.java", "kind", "CREATE", "content", "class Resume {}")));
        request("POST", "/api/auth/logout", null, null, 204);
        request("POST", base + "/file-batches", batch, "revoked-session", 403, credential);
        // Explicit replacement-session fixture; a real login never restores the page secret.
        token = secrets.token(); csrf = secrets.token();
        jdbc.update("INSERT INTO identity_session(token_hash, user_id, auth_version, csrf_token, issued_at, last_activity) SELECT ?, id, auth_version, ?, now(), now() FROM identity_user WHERE id = ?::uuid",
            IdentitySecrets.digest(token), csrf, owner);
        assertThat(request("POST", base + "/file-batches", batch, "resumed-session", 200, credential).path("data").path("revision").asText()).isEqualTo("1");
    }

    @Test
    void passwordResetFencesDelayedWritesBeforeReportingSuccess() throws Exception {
        String exercise = readyExercise("delayed-write");
        String base = "/api/exercises/" + exercise;
        String credential = request("POST", base + "/control/acquire", Map.of(), "control", 200).path("data").path("credential").asText();
        request("POST", base + "/start", Map.of(), "start", 200, credential);
        String victimToken = token;
        String victimCsrf = csrf;
        String victim = jdbc.queryForObject("SELECT user_id::text FROM identity_session WHERE token_hash = ?", String.class, IdentitySecrets.digest(token));
        String instance = request("GET", base, null, null, 200).path("data").path("workspaceInstanceId").asText();
        long oldEpoch = jdbc.queryForObject("SELECT gate_epoch FROM workspace_control WHERE exercise_id = ?::uuid", Long.class, exercise);
        String container = System.getenv("SANDBOX_TEST_CONTAINER");
        assertThat(container).matches("[0-9a-f]{64}");
        docker("pause", container);
        try {
            JsonNode accepted = request("POST", base + "/file-batches", Map.of("expectedRevision", "0", "changes",
                List.of(Map.of("path", "src/test/java/Delayed.java", "kind", "CREATE", "content", "class Delayed {}"))), "delayed-save", 202, credential).path("data");
            assertThat(accepted.path("operationId").asText()).isNotBlank();
            seedDisposableIdentity();
            jdbc.update("UPDATE identity_user SET role = 'ADMIN' WHERE id = (SELECT user_id FROM identity_session WHERE token_hash = ?)", IdentitySecrets.digest(token));
            JsonNode reset = request("POST", "/api/admin/users/" + victim + "/password-reset", Map.of(), "reset-delayed", 202).path("data");
            assertThat(reset.path("temporaryPassword").isMissingNode() || reset.path("temporaryPassword").isNull()).isTrue();
        } finally { docker("unpause", container); }
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        HttpResponse<String> response;
        do {
            HttpRequest reset = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/users/" + victim + "/password-reset"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", "codecraft_session=" + token)
                .header("Origin", "http://localhost:5173").header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json").header("Idempotency-Key", "reset-delayed")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            response = http.send(reset, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) break;
            assertThat(response.statusCode()).isEqualTo(202);
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        assertThat(response.statusCode()).isEqualTo(200);
        token = victimToken; csrf = victimCsrf;
        request("GET", base, null, null, 401);
        long before = sandboxGateway.query(instance).getRevision();
        ApplyBatchRequest late = ApplyBatchRequest.newBuilder().setWorkspaceInstanceId(instance)
            .setOperationId(UUID.randomUUID().toString()).setRequestId(UUID.randomUUID().toString())
            .setGateEpoch(oldEpoch).setExpectedRevision(before).setNotAfterUnixMillis(Instant.now().plusSeconds(30).toEpochMilli())
            .addChanges(FileChange.newBuilder().setPath("src/test/java/Late.java").setKind("CREATE").setContent("class Late {}")) .build();
        assertThatThrownBy(() -> sandboxGateway.apply(late)).isInstanceOfSatisfying(StatusRuntimeException.class,
            error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
        assertThat(sandboxGateway.query(instance).getRevision()).isEqualTo(before);
    }

    @Test
    void readyAbandonmentClosesActivatedGateBeforeCancellation() throws Exception {
        String exercise = readyExercise("abandon-controlled");
        String base = "/api/exercises/" + exercise;
        request("POST", base + "/control/acquire", Map.of(), "control", 200);
        JsonNode abandoned = request("POST", base + "/abandon", Map.of(), "abandon", 202).path("data");
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode result;
        do {
            result = request("GET", abandoned.path("queryUrl").asText(), null, null, 200).path("data");
            if (result.path("state").asText().equals("SUCCEEDED")) break;
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        assertThat(result.path("state").asText()).isEqualTo("SUCCEEDED");
        request("GET", base + "/files?path=pom.xml", null, null, 404);
    }

    private String readyExercise(String key) throws Exception {
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        String exercise = request("POST", "/api/exercises", Map.of("problemVersionId", version), key, 202).path("data").path("exerciseId").asText();
        waitForState(exercise, "READY");
        return exercise;
    }

    @Test
    void alteredPublicationBytesCannotReuseTheValidationReceipt() throws Exception {
        JsonNode before = request("GET", "/api/problems", null, null, 200).path("data");
        JsonNode artifact = json.readTree(Files.readAllBytes(Path.of(workspaceProperties.publicationFile())));
        ((ObjectNode) artifact.path("initialFiles")).put("src/main/java/interview/CartTotal.java", "not compilable");
        Path altered = Files.createTempFile("workspace-altered-publication-", ".json");
        try {
            Files.writeString(altered, json.writeValueAsString(artifact));
            assertThatThrownBy(() -> publications.publish(altered)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid maintainer publication contract");
            assertThat(request("GET", "/api/problems", null, null, 200).path("data")).isEqualTo(before);
        } finally {
            Files.deleteIfExists(altered);
        }
    }

    @Test
    void publishedProblemCreatesAnIsolatedReadableWorkspace() throws Exception {
        JsonNode problems = request("GET", "/api/problems", null, null, 200).path("data");
        assertThat(problems.isArray()).isTrue();
        assertThat(problems).hasSize(1);
        String version = problems.get(0).path("problemVersionId").asText();
        assertThat(version).isNotBlank();
        JsonNode accepted = request("POST", "/api/exercises", Map.of("problemVersionId", version), "create-workspace-key", 202).path("data");
        String exercise = accepted.path("exerciseId").asText();
        assertThat(exercise).isNotBlank();
        assertThat(accepted.path("operationId").asText()).isNotBlank();
        JsonNode snapshot = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            snapshot = request("GET", "/api/exercises/" + exercise, null, null, 200).path("data");
            if ("READY".equals(snapshot.path("state").asText())) break;
            Thread.sleep(100);
        }
        assertThat(snapshot.path("state").asText()).isEqualTo("READY");
        assertThat(snapshot.path("revision").isTextual()).isTrue();
        assertThat(snapshot.path("revision").asText()).isEqualTo("0");
        JsonNode file = request("GET", "/api/exercises/" + exercise + "/files?path=src/main/java/interview/CartTotal.java", null, null, 200).path("data");
        assertThat(file.path("content").asText()).contains("class CartTotal");
        JsonNode replay = request("POST", "/api/exercises", Map.of("problemVersionId", version), "create-workspace-key", 202).path("data");
        assertThat(replay.path("exerciseId").asText()).isEqualTo(exercise);
        request("POST", "/api/exercises", Map.of("problemVersionId", version), "different-create-key", 409);
    }

    @Test
    void startIsAuthorizedIdempotentAndDoesNotResetTheDeadline() throws Exception {
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        String exercise = request("POST", "/api/exercises", Map.of("problemVersionId", version), "start-fixture", 202)
            .path("data").path("exerciseId").asText();
        long waitUntil = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!"READY".equals(request("GET", "/api/exercises/" + exercise, null, null, 200).path("data").path("state").asText())
            && System.nanoTime() < waitUntil) Thread.sleep(100);
        String credential = request("POST", "/api/exercises/" + exercise + "/control/acquire", Map.of(), "control-start", 200).path("data").path("credential").asText();
        JsonNode started = request("POST", "/api/exercises/" + exercise + "/start", Map.of(), "start-once", 200, credential).path("data");
        assertThat(started.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(started.path("startedAt").asText()).isNotBlank();
        assertThat(Duration.between(Instant.parse(started.path("startedAt").asText()),
            Instant.parse(started.path("deadlineAt").asText()))).isEqualTo(Duration.ofSeconds(2700));
        JsonNode replay = request("POST", "/api/exercises/" + exercise + "/start", Map.of(), "start-once", 200, credential).path("data");
        assertThat(replay.path("deadlineAt")).isEqualTo(started.path("deadlineAt"));
        request("POST", "/api/exercises/" + exercise + "/start", Map.of(), "another-start", 409, credential);
    }

    @Test
    void privateWorkspaceAndOperationAreHiddenFromAnotherUser() throws Exception {
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        JsonNode accepted = request("POST", "/api/exercises", Map.of("problemVersionId", version), "private-fixture", 202).path("data");
        String exercise = accepted.path("exerciseId").asText();
        String operation = accepted.path("operationId").asText();
        String ownerToken = token;
        String ownerCsrf = csrf;
        seedDisposableIdentity();
        request("GET", "/api/exercises/" + exercise, null, null, 404);
        request("GET", "/api/exercises/" + exercise + "/files?path=pom.xml", null, null, 404);
        request("GET", "/api/operations/" + operation, null, null, 404);
        request("POST", "/api/exercises/" + exercise + "/start", Map.of(), "foreign-start", 404);
        token = ownerToken;
        csrf = ownerCsrf;
        long waitUntil = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!"READY".equals(request("GET", "/api/exercises/" + exercise, null, null, 200).path("data").path("state").asText())
            && System.nanoTime() < waitUntil) Thread.sleep(100);
        request("GET", "/api/exercises/" + exercise + "/files?path=../pom.xml", null, null, 400);
        request("GET", "/api/exercises/" + exercise + "/files?path=reference/Solution.java", null, null, 404);
        request("POST", "/api/exercises", Map.of("problemVersionId", UUID.randomUUID().toString()), "private-fixture", 409);
        request("POST", "/api/auth/logout", null, null, 204);
        request("GET", "/api/exercises/" + exercise + "/files?path=pom.xml", null, null, 401);
    }

    @Test
    void abandoningReadyWorkspaceReleasesAdmissionAndHidesItsFiles() throws Exception {
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        String exercise = request("POST", "/api/exercises", Map.of("problemVersionId", version), "abandon-fixture", 202)
            .path("data").path("exerciseId").asText();
        long waitUntil = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!"READY".equals(request("GET", "/api/exercises/" + exercise, null, null, 200).path("data").path("state").asText())
            && System.nanoTime() < waitUntil) Thread.sleep(100);
        JsonNode abandoned = request("POST", "/api/exercises/" + exercise + "/abandon", Map.of(), "abandon-once", 202).path("data");
        assertThat(abandoned.path("operationId").asText()).isNotBlank();
        assertThat(request("GET", "/api/exercises/" + exercise, null, null, 200).path("data").path("state").asText()).isEqualTo("ENDED");
        request("GET", "/api/exercises/" + exercise + "/files?path=pom.xml", null, null, 404);
        request("GET", "/api/exercises/current", null, null, 404);
        JsonNode next = request("POST", "/api/exercises", Map.of("problemVersionId", version), "after-abandon", 202).path("data");
        assertThat(next.path("exerciseId").asText()).isNotEqualTo(exercise);
        JsonNode replay = request("POST", "/api/exercises/" + exercise + "/abandon", Map.of(), "abandon-once", 202).path("data");
        assertThat(replay.path("operationId")).isEqualTo(abandoned.path("operationId"));
    }

    @Test
    void confirmedPreparationFailureCanRetryWithANewInstanceThenBeAbandoned() throws Exception {
        String missingVersion = UUID.randomUUID().toString();
        // Deployment fault fixture: Java has a version which the real Go publisher does not have.
        jdbc.update("""
            INSERT INTO problem_version(id, problem_id, version, package_sha256, execution_image_digest, platform, artifact)
            SELECT ?::uuid, problem_id, '999.0.0', package_sha256, execution_image_digest, platform, artifact
            FROM problem_version WHERE version <> '999.0.0' ORDER BY version LIMIT 1
            """, missingVersion);
        String exercise = null;
        try {
            exercise = request("POST", "/api/exercises", Map.of("problemVersionId", missingVersion), "missing-publication", 202)
                .path("data").path("exerciseId").asText();
            JsonNode failed = waitForState(exercise, "PREPARATION_FAILED");
            assertThat(failed.path("preparationFailureReason").asText()).isEqualTo("PREPARATION_PRECONDITION_FAILED");
            assertThat(failed.path("cleanupState").asText()).isEqualTo("SUCCEEDED");
            String oldInstance = failed.path("workspaceInstanceId").asText();
            request("GET", "/api/exercises/" + exercise + "/files?path=pom.xml", null, null, 409);
            JsonNode retry = request("POST", "/api/exercises/" + exercise + "/preparation-retry", Map.of(), "retry-once", 202).path("data");
            JsonNode secondFailure = waitForState(exercise, "PREPARATION_FAILED");
            assertThat(secondFailure.path("workspaceInstanceId").asText()).isNotEqualTo(oldInstance);
            JsonNode replay = request("POST", "/api/exercises/" + exercise + "/preparation-retry", Map.of(), "retry-once", 202).path("data");
            assertThat(replay.path("operationId")).isEqualTo(retry.path("operationId"));
            JsonNode abandon = request("POST", "/api/exercises/" + exercise + "/abandon", Map.of(), "abandon-failure", 202).path("data");
            long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            JsonNode operation;
            do {
                operation = request("GET", abandon.path("queryUrl").asText(), null, null, 200).path("data");
                if (operation.path("state").asText().equals("SUCCEEDED")) break;
                Thread.sleep(100);
            } while (System.nanoTime() < until);
            assertThat(operation.path("state").asText()).isEqualTo("SUCCEEDED");
            JsonNode ended = request("GET", "/api/exercises/" + exercise, null, null, 200).path("data");
            assertThat(ended.path("endReason").asText()).isEqualTo("PREPARATION_ABANDONED");
            assertThat(ended.path("cleanupState").asText()).isEqualTo("SUCCEEDED");
            request("GET", "/api/exercises/current", null, null, 404);
        } finally {
            if (exercise != null) {
                jdbc.update("DELETE FROM workspace_operation WHERE exercise_id = ?::uuid", exercise);
                jdbc.update("DELETE FROM workspace_exercise WHERE id = ?::uuid", exercise);
            }
            jdbc.update("DELETE FROM problem_version WHERE id = ?::uuid", missingVersion);
        }
    }

    @Test
    void concurrentDifferentKeysCannotCreateTwoUnfinishedExercises() throws Exception {
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        String body = json.writeValueAsString(Map.of("problemVersionId", version));
        HttpRequest first = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/exercises"))
            .timeout(Duration.ofSeconds(10)).header("Cookie", "codecraft_session=" + token)
            .header("Origin", "http://localhost:5173").header("X-CSRF-TOKEN", csrf)
            .header("Content-Type", "application/json").header("Idempotency-Key", "concurrent-a")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpRequest second = HttpRequest.newBuilder(first, (name, value) -> !name.equalsIgnoreCase("Idempotency-Key"))
            .header("Idempotency-Key", "concurrent-b").build();
        CompletableFuture<HttpResponse<String>> a = http.sendAsync(first, HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> b = http.sendAsync(second, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> resultA = a.get();
        HttpResponse<String> resultB = b.get();
        assertThat(List.of(resultA.statusCode(), resultB.statusCode())).containsExactlyInAnyOrder(202, 409);
        JsonNode winner = json.readTree(resultA.statusCode() == 202 ? resultA.body() : resultB.body()).path("data");
        assertThat(request("GET", "/api/exercises/current", null, null, 200).path("data").path("exerciseId"))
            .isEqualTo(winner.path("exerciseId"));
    }

    @Test
    void sixteenBrokenCleanupInstancesCannotStarveNewPreparation() throws Exception {
        String container = System.getenv("SANDBOX_TEST_CONTAINER");
        assertThat(container).matches("[0-9a-f]{64}");
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        String exercise = request("POST", "/api/exercises", Map.of("problemVersionId", version), "fairness-base", 202).path("data").path("exerciseId").asText();
        waitForState(exercise, "READY");
        request("POST", "/api/exercises/" + exercise + "/abandon", Map.of(), "fairness-abandon", 202);
        List<String> operations = new ArrayList<>();
        Path fault = Files.createTempFile("workspace-owned-fault-", ".txt");
        try {
            Files.writeString(fault, "not a workspace directory");
            for (int index = 0; index < 16; index++) {
                String instance = UUID.randomUUID().toString();
                String operation = UUID.randomUUID().toString();
                docker("cp", fault.toString(), container + ":/var/lib/codecraft/workspaces/" + instance);
                jdbc.update("INSERT INTO workspace_operation(id, user_id, exercise_id, workspace_instance_id, kind, target, request_key, fingerprint, state, created_at) SELECT ?::uuid, user_id, id, ?::uuid, 'ABANDON', id::text, ?, repeat('a', 64), 'PENDING_RECONCILIATION', now() - interval '1 day' FROM workspace_exercise WHERE id = ?::uuid",
                    operation, instance, operation, exercise);
                operations.add(operation);
            }
            String healthy = request("POST", "/api/exercises", Map.of("problemVersionId", version), "fairness-healthy", 202).path("data").path("exerciseId").asText();
            waitForState(healthy, "READY");
            for (String operation : operations) {
                assertThat(request("GET", "/api/operations/" + operation, null, null, 200).path("data").path("state").asText()).isEqualTo("PENDING_RECONCILIATION");
            }
        } finally {
            Files.deleteIfExists(fault);
            for (String operation : operations) jdbc.update("DELETE FROM workspace_operation WHERE id = ?::uuid", operation);
        }
    }

    @Test
    void revokedPreparationIsCleanedAfterSandboxRecovery() throws Exception {
        String container = System.getenv("SANDBOX_TEST_CONTAINER");
        assertThat(container).matches("[0-9a-f]{64}");
        String owner = jdbc.queryForObject("SELECT user_id::text FROM identity_session WHERE token_hash = ?", String.class, IdentitySecrets.digest(token));
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        String exercise;
        docker("pause", container);
        try {
            exercise = request("POST", "/api/exercises", Map.of("problemVersionId", version), "revoked-while-offline", 202).path("data").path("exerciseId").asText();
            // Recovery transport is tested here; expiry decisions are tested with
            // a controllable Clock in PreparationAuthorizationTest, without waiting.
            jdbc.update("UPDATE identity_session SET revoked = true WHERE token_hash = ?", IdentitySecrets.digest(token));
        } finally {
            docker("unpause", container);
        }
        request("GET", "/api/exercises/current", null, null, 401);
        token = secrets.token();
        csrf = secrets.token();
        jdbc.update("INSERT INTO identity_session(token_hash, user_id, auth_version, csrf_token, issued_at, last_activity) SELECT ?, id, auth_version, ?, now(), now() FROM identity_user WHERE id = ?::uuid",
            IdentitySecrets.digest(token), csrf, owner);
        JsonNode failed = waitForState(exercise, "PREPARATION_FAILED");
        assertThat(failed.path("preparationFailureReason").asText()).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(failed.path("cleanupState").asText()).isEqualTo("SUCCEEDED");
        assertThat(failed.path("paths").size()).isZero();
        request("POST", "/api/exercises/" + exercise + "/preparation-retry", Map.of(), "new-session-retry", 202);
        waitForState(exercise, "READY");
    }

    @Test
    void passwordResetWaitsForDurableFencingWhenPreparationIsUnresolved() throws Exception {
        String container = System.getenv("SANDBOX_TEST_CONTAINER");
        assertThat(container).matches("[0-9a-f]{64}");
        String victimToken = token;
        String victimCsrf = csrf;
        String victim = jdbc.queryForObject("SELECT user_id::text FROM identity_session WHERE token_hash = ?", String.class, IdentitySecrets.digest(token));
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        String revocationId;
        docker("pause", container);
        try {
            request("POST", "/api/exercises", Map.of("problemVersionId", version), "revocation-pending-fixture", 202);
            seedDisposableIdentity();
            jdbc.update("UPDATE identity_user SET role = 'ADMIN' WHERE id = (SELECT user_id FROM identity_session WHERE token_hash = ?)", IdentitySecrets.digest(token));
            JsonNode pending = request("POST", "/api/admin/users/" + victim + "/password-reset", Map.of(), "fenced-reset", 202).path("data");
            revocationId = pending.path("operationId").asText();
            assertThat(revocationId).isNotBlank();
            assertThat(request("GET", pending.path("queryUrl").asText(), null, null, 200).path("data").path("state").asText()).isEqualTo("PENDING");
            assertThat(pending.path("temporaryPassword").isMissingNode() || pending.path("temporaryPassword").isNull()).isTrue();
        } finally {
            docker("unpause", container);
        }
        // Completion is an explicit same-key retry, never a guessed success after an RPC timeout.
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        HttpResponse<String> result;
        do {
            HttpRequest reset = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/users/" + victim + "/password-reset"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", "codecraft_session=" + token)
                .header("Origin", "http://localhost:5173").header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json").header("Idempotency-Key", "fenced-reset")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            result = http.send(reset, HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() == 200) break;
            assertThat(result.statusCode()).isEqualTo(202);
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json.readTree(result.body()).path("data").path("operationId").asText()).isEqualTo(revocationId);
        assertThat(request("GET", "/api/operations/" + revocationId, null, null, 200).path("data").path("state").asText()).isEqualTo("SUCCEEDED");
        token = victimToken;
        csrf = victimCsrf;
        request("GET", "/api/exercises/current", null, null, 401);
    }

    private void docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Path reports = Path.of("target", "failsafe-reports");
        Files.createDirectories(reports);
        Path output = Files.createTempFile(reports, "docker-control-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Owned sandbox control timed out; diagnostics: " + output);
        }
        assertThat(process.exitValue()).as("docker %s owned sandbox; diagnostics: %s", arguments[0], output).isZero();
    }

    private JsonNode waitForState(String exercise, String expected) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode snapshot;
        do {
            snapshot = request("GET", "/api/exercises/" + exercise, null, null, 200).path("data");
            if (snapshot.path("state").asText().equals(expected)) return snapshot;
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        assertThat(snapshot.path("state").asText()).as("Exercise %s last snapshot: %s", exercise, snapshot).isEqualTo(expected);
        return snapshot;
    }

    private JsonNode request(String method, String path, Object body, String key, int expected) throws Exception {
        return request(method, path, body, key, expected, null);
    }

    private JsonNode request(String method, String path, Object body, String key, int expected, String credential) throws Exception {
        HttpResponse<String> response = http.send(buildRequest(method, path, body, key, credential), HttpResponse.BodyHandlers.ofString());
        JsonNode result = response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
        assertThat(response.statusCode()).as("%s %s: code=%s requestId=%s", method, path,
            result.path("code").asText(), result.path("requestId").asText()).isEqualTo(expected);
        return result;
    }

    private HttpRequest buildRequest(String method, String path, Object body, String key, String credential) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Cookie", "codecraft_session=" + token)
            .header("Origin", "http://localhost:5173").header("X-CSRF-TOKEN", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (credential != null) request.header("X-Operation-Credential", credential);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return request.build();
    }
}
