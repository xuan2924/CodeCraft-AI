package ai.codecraft.modules.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.identity.service.IdentitySecrets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.sql.Timestamp;
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
        JsonNode started = request("POST", "/api/exercises/" + exercise + "/start", Map.of(), "start-once", 200).path("data");
        assertThat(started.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(started.path("startedAt").asText()).isNotBlank();
        assertThat(Duration.between(Instant.parse(started.path("startedAt").asText()),
            Instant.parse(started.path("deadlineAt").asText()))).isEqualTo(Duration.ofSeconds(2700));
        JsonNode replay = request("POST", "/api/exercises/" + exercise + "/start", Map.of(), "start-once", 200).path("data");
        assertThat(replay.path("deadlineAt")).isEqualTo(started.path("deadlineAt"));
        request("POST", "/api/exercises/" + exercise + "/start", Map.of(), "another-start", 409);
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
            FROM problem_version WHERE version = '1.0.0'
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
    void expiredPreparationAuthorityCannotCreateFilesAfterSandboxRecovery() throws Exception {
        String container = System.getenv("SANDBOX_TEST_CONTAINER");
        assertThat(container).matches("[0-9a-f]{64}");
        String owner = jdbc.queryForObject("SELECT user_id::text FROM identity_session WHERE token_hash = ?", String.class, IdentitySecrets.digest(token));
        String version = request("GET", "/api/problems", null, null, 200).path("data").get(0).path("problemVersionId").asText();
        String exercise;
        docker("pause", container);
        try {
            jdbc.update("UPDATE identity_session SET issued_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(30)).plusSeconds(5)), IdentitySecrets.digest(token));
            exercise = request("POST", "/api/exercises", Map.of("problemVersionId", version), "expires-while-offline", 202).path("data").path("exerciseId").asText();
            Thread.sleep(5500);
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
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Owned sandbox control timed out");
        }
        assertThat(process.exitValue()).as("docker " + arguments[0] + " owned sandbox").isZero();
    }

    private JsonNode waitForState(String exercise, String expected) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode snapshot;
        do {
            snapshot = request("GET", "/api/exercises/" + exercise, null, null, 200).path("data");
            if (snapshot.path("state").asText().equals(expected)) return snapshot;
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        assertThat(snapshot.path("state").asText()).isEqualTo(expected);
        return snapshot;
    }

    private JsonNode request(String method, String path, Object body, String key, int expected) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Cookie", "codecraft_session=" + token)
            .header("Origin", "http://localhost:5173").header("X-CSRF-TOKEN", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path).isEqualTo(expected);
        return response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
    }
}
