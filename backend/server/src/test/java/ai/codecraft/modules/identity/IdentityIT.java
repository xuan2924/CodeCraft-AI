package ai.codecraft.modules.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.identity.config.IdentityBootstrap;
import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.identity.model.dto.CreatedAccountResponse;
import ai.codecraft.modules.identity.model.dto.PasswordResetResponse;
import ai.codecraft.modules.identity.model.dto.IdentityRevokedEventDTO;
import ai.codecraft.modules.identity.model.entity.UserEntity;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import java.sql.Timestamp;
import org.springframework.boot.DefaultApplicationArguments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

// scripts/test-identity.mjs supplies a fresh disposable PostgreSQL; never use the development database.
@EnabledIfEnvironmentVariable(named = "IDENTITY_IT_RUN", matches = "disposable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "identity.public-origin=http://localhost:5173")
@Import(IdentityIT.TimeFixture.class)
class IdentityIT {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PASSWORD = "test-password-only";
    private static final String NEW_PASSWORD = "changed-password-only";
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private IdentityTransactionService store;
    @Autowired private IdentitySecrets secrets;
    @Autowired private MutableClock clock;
    @Autowired private RevocationProbe notifications;

    @TestConfiguration
    static class TimeFixture {
        @Bean @Primary MutableClock controlledClock() { return new MutableClock(); }
        @Bean RevocationProbe revocationProbe() { return new RevocationProbe(); }
    }
    static final class RevocationProbe {
        final CopyOnWriteArrayList<IdentityRevokedEventDTO> events = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        @EventListener public void revoked(IdentityRevokedEventDTO event) {
            events.add(event);
            if (fail) throw new IllegalStateException("notification fault fixture");
        }
    }
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        void at(Instant value) { now.set(value); }
    }

    @BeforeEach
    void resetDisposableFixture() {
        clock.at(START);
        notifications.events.clear();
        notifications.fail = false;
        jdbc.execute("TRUNCATE workspace_task_event, workspace_task_stream, workspace_test_stop, workspace_test_task, workspace_mutation, workspace_transition, workspace_control, workspace_operation, workspace_exercise, identity_revocation_intent, identity_receipt, identity_session, identity_anonymous_csrf, identity_rate_limit, identity_user");
        store.bootstrap("admin_test", secrets.hash(PASSWORD));
    }

    @Test
    void migratedSchemaUsesLogicalReferencesOnly() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND connamespace = current_schema()::regnamespace", Integer.class)).isZero();
    }

    @Test
    void completeAccountFlowEnforcesRestrictionRoleAndIdempotency() throws Exception {
        try (Browser admin = new Browser(); Browser user = new Browser()) {
            admin.login("ADMIN_TEST", PASSWORD, 200);
            assertThat(admin.body.path("data").path("passwordChangeRequired").asBoolean()).isTrue();
            admin.csrf();
            admin.post("/api/admin/users", Map.of("username", "invited"), "create-key-0001", 403);
            assertThat(admin.body.path("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
            admin.password(PASSWORD, NEW_PASSWORD);
            admin.get("/api/auth/session", 401);
            admin.login("admin_test", NEW_PASSWORD, 200);
            admin.csrf();
            admin.post("/api/admin/users", Map.of("username", "Invited"), "create-key-0001", 201);
            String temporary = admin.body.path("data").path("temporaryPassword").asText();
            String userId = admin.body.path("data").path("userId").asText();
            assertThat(temporary).hasSize(43);
            admin.post("/api/admin/users", Map.of("username", "invited"), "create-key-0001", 200);
            assertThat(admin.body.path("data").path("credentialDelivered").asBoolean()).isFalse();
            assertThat(admin.body.path("data").path("temporaryPassword").isNull()).isTrue();
            assertThat(admin.body.path("data").path("userId").asText()).isEqualTo(userId);
            admin.post("/api/admin/users", Map.of("username", "different"), "create-key-0001", 409);
            admin.post("/api/admin/users", Map.of("username", "invited"), "create-key-0002", 409);
            user.login("invited", temporary, 200);
            user.password(temporary, "😀".repeat(8));
            user.login("invited", "😀".repeat(8), 200);
            user.csrf();
            user.post("/api/admin/users", Map.of("username", "forbidden"), "create-key-0003", 403);
            String rawCookie = user.cookies.get(IdentityHttp.SESSION_COOKIE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session WHERE token_hash = ?", Integer.class,
                IdentitySecrets.digest(rawCookie))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT password_hash FROM identity_user WHERE username='invited'", String.class))
                .startsWith("$argon2id$").doesNotContain(temporary);
            user.post("/api/auth/logout", Map.of(), null, 204);
            user.get("/api/auth/session", 401);
            user.csrf();
            user.post("/api/auth/logout", Map.of(), null, 204);
            admin.get("/api/auth/session", 200);
        }
    }

    @Test
    void csrfOriginRotationCookiesAndAnonymousExpiry() throws Exception {
        try (Browser browser = new Browser()) {
            browser.post("/api/auth/login", Map.of("username", "admin_test", "password", PASSWORD), null, 403);
            browser.csrf();
            String anonymous = browser.token;
            browser.origin = "https://attacker.invalid";
            browser.post("/api/auth/login", Map.of("username", "admin_test", "password", PASSWORD), null, 403);
            browser.origin = null;
            browser.post("/api/auth/login", Map.of("username", "admin_test", "password", PASSWORD), null, 403);
            browser.origin = "http://localhost:5173";
            browser.login("admin_test", PASSWORD, 200);
            assertThat(browser.last.headers().allValues("set-cookie")).anySatisfy(value -> {
                assertThat(value).startsWith("codecraft_session=").contains("HttpOnly", "SameSite=Lax", "Path=/")
                    .doesNotContain("Secure", "Domain=");
            });
            browser.token = anonymous;
            browser.post("/api/auth/logout", Map.of(), null, 403);
            browser.csrf();
            assertThat(browser.token).isNotEqualTo(anonymous);
            browser.post("/api/auth/logout", Map.of(), null, 204);
            browser.csrf();
            clock.at(START.plus(Duration.ofMinutes(15)));
            browser.post("/api/auth/logout", Map.of(), null, 403);
            browser.csrf();
            browser.post("/api/auth/logout", Map.of(), null, 204);
        }
    }

    @Test
    void accountAndAddressAttemptsAreBoundedAndFailuresDoNotCreateSessions() throws Exception {
        assertThat(store.admit(secrets.fingerprint("fixture-account"), secrets.fingerprint("fixture-address"))).isTrue();
        try (Browser browser = new Browser()) {
            browser.csrf();
            for (int index = 0; index < 5; index++) {
                browser.post("/api/auth/login", Map.of("username", index == 0 ? "admin_test" : "unknown_" + index,
                    "password", "incorrect-password"), null, 401);
                assertThat(browser.body.path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
            }
            browser.post("/api/auth/login", Map.of("username", "admin_test", "password", PASSWORD), null, 429);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session", Integer.class)).isZero();
            clock.at(START.plusSeconds(60));
            browser.login("admin_test", PASSWORD, 200);
        }
        for (int index = 0; index < 5; index++) {
            assertThat(store.admit(secrets.fingerprint("account"), secrets.fingerprint("address", "ip" + index))).isTrue();
        }
        assertThat(store.admit(secrets.fingerprint("account"), secrets.fingerprint("address", "new-ip"))).isFalse();
    }

    @Test
    void queriesDoNotRenewAndExpiredSessionsCannotRevive() throws Exception {
        try (Browser browser = new Browser()) {
            browser.login("admin_test", PASSWORD, 200);
            clock.at(START.plus(Duration.ofDays(6)));
            browser.get("/api/auth/session", 200);
            browser.csrf();
            assertThat(browser.body.path("data").has("token")).isTrue();
            clock.at(START.plus(Duration.ofDays(7)));
            browser.get("/api/auth/session", 401);
        }
    }

    @Test
    void explicitActionsRenewButNeverCrossThirtyDays() throws Exception {
        try (Browser browser = administrator()) {
            clock.at(START.plus(Duration.ofDays(6)));
            browser.csrf();
            browser.post("/api/admin/users", Map.of("username", "renew_one"), "renew-key-one", 201);
            clock.at(START.plus(Duration.ofDays(12)));
            browser.get("/api/auth/session", 200);
            assertThat(browser.body.path("data").path("expiresAt").asText()).isEqualTo(START.plus(Duration.ofDays(13)).toString());
            for (int day : new int[] {12, 18, 24, 29}) {
                clock.at(START.plus(Duration.ofDays(day)));
                browser.post("/api/admin/users", Map.of("username", "renew_" + day), "renew-key-" + day, 201);
            }
            clock.at(START.plus(Duration.ofDays(30)));
            browser.get("/api/auth/session", 401);
        }
    }

    @Test
    void boundedAnonymousContextsDoNotEvictExistingVisitors() throws Exception {
        try (Browser existing = new Browser(); Browser fresh = new Browser()) {
            existing.csrf();
            String token = existing.token;
            jdbc.update("INSERT INTO identity_anonymous_csrf SELECT lpad(i::text,64,'0'), 'fixture', ? FROM generate_series(1,999) i",
                Timestamp.from(START.plusSeconds(900)));
            fresh.get("/api/auth/csrf", 429);
            existing.csrf();
            assertThat(existing.token).isEqualTo(token);
            clock.at(START.plusSeconds(900));
            fresh.csrf();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_anonymous_csrf", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void concurrentCreateDeliversOnlyOneCredentialAndBootstrapDoesNotOverwrite() throws Exception {
        try (Browser admin = administrator()) {
            UserEntity before = store.findUser("admin_test");
            String sessionHash = IdentitySecrets.digest(admin.cookies.get(IdentityHttp.SESSION_COOKIE));
            String encoded = secrets.hash(PASSWORD);
            CompletableFuture<CreatedAccountResponse> first = CompletableFuture.supplyAsync(() -> store.createUser(
                sessionHash, "concurrent", PASSWORD, encoded, "same-key-0001", secrets.fingerprint("concurrent")));
            CompletableFuture<CreatedAccountResponse> second = CompletableFuture.supplyAsync(() -> store.createUser(
                sessionHash, "concurrent", PASSWORD, encoded, "same-key-0001", secrets.fingerprint("concurrent")));
            assertThat(first.join().credentialDelivered() ^ second.join().credentialDelivered()).isTrue();
            assertThat(first.join().userId()).isEqualTo(second.join().userId());
            store.bootstrap("admin_test", secrets.hash("do-not-overwrite"));
            assertThat(store.findUser("admin_test").passwordHash()).isEqualTo(before.passwordHash());
        }
    }

    @Test
    void passwordChangeRevokesAllSessionsAndRejectsAStaleLoginVerification() throws Exception {
        try (Browser first = new Browser(); Browser second = new Browser()) {
            first.login("admin_test", PASSWORD, 200);
            second.login("admin_test", PASSWORD, 200);
            UserEntity verifiedBeforeChange = store.findUser("admin_test");
            first.password(PASSWORD, NEW_PASSWORD);
            second.get("/api/auth/session", 401);
            assertThatThrownBy(() -> store.login(verifiedBeforeChange, IdentitySecrets.digest(secrets.token()),
                secrets.token(), null, null, null)).isInstanceOf(IdentityFailure.class)
                .hasMessage("INVALID_CREDENTIALS");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session WHERE NOT revoked", Integer.class)).isZero();
        }
    }

    @Test
    void emptyDatabaseBootstrapIsSerializedAndMissingCredentialsFailClosed() {
        jdbc.execute("TRUNCATE workspace_mutation, workspace_transition, workspace_control, workspace_operation, workspace_exercise, identity_revocation_intent, identity_receipt, identity_session, identity_user");
        IdentitySettings missing = new IdentitySettings("http://localhost:5173", "01".repeat(32), "", "");
        IdentityBootstrap bootstrap = new IdentityBootstrap(store, secrets, missing);
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("bootstrap administrator is required");
        assertThat(store.hasAdministrator()).isFalse();
        String encoded = secrets.hash(PASSWORD);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> store.bootstrap("first_admin", encoded));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> store.bootstrap("second_admin", encoded));
        CompletableFuture.allOf(first, second).join();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_user WHERE role='ADMIN'", Integer.class)).isEqualTo(1);
        // After initial creation the bootstrap secrets may be removed entirely.
        bootstrap.run(new DefaultApplicationArguments());
    }

    @Test
    void failedReceiptInsertRollsBackPasswordAndRevocation() throws Exception {
        try (Browser browser = new Browser()) {
            browser.login("admin_test", PASSWORD, 200);
            String hash = IdentitySecrets.digest(browser.cookies.get(IdentityHttp.SESSION_COOKIE));
            UserEntity before = store.findUser("admin_test");
            // Explicit database fault fixture, not a normal API request.
            assertThatThrownBy(() -> store.changePassword(hash, before, secrets.hash(NEW_PASSWORD),
                "x".repeat(129), secrets.fingerprint(PASSWORD, NEW_PASSWORD))).isInstanceOf(RuntimeException.class);
            assertThat(store.findUser("admin_test").passwordHash()).isEqualTo(before.passwordHash());
            browser.get("/api/auth/session", 200);
        }
    }

    @Test
    void administratorResetRevokesEveryBrowserAndReplayNeverDeliversSecrets() throws Exception {
        try (Browser admin = administrator(); Browser first = new Browser(); Browser second = new Browser()) {
            admin.post("/api/admin/users", Map.of("username", "reset_target"), "create-reset-0001", 201);
            String target = admin.body.path("data").path("userId").asText();
            String oldPassword = admin.body.path("data").path("temporaryPassword").asText();
            first.login("reset_target", oldPassword, 200);
            second.login("reset_target", oldPassword, 200);
            String path = "/api/admin/users/" + target + "/password-reset";
            UserEntity before = store.findUser("reset_target");
            admin.post(path, Map.of(), "reset-key-0001", 200);
            String replacement = admin.body.path("data").path("temporaryPassword").asText();
            String operation = admin.body.path("data").path("operationId").asText();
            assertThat(replacement.length() == 43).isTrue();
            first.get("/api/auth/session", 401);
            second.get("/api/auth/session", 401);
            admin.post(path, Map.of(), "reset-key-0001", 200);
            assertThat(admin.body.path("data").path("credentialDelivered").asBoolean()).isFalse();
            assertThat(admin.body.path("data").path("temporaryPassword").isNull()).isTrue();
            assertThat(admin.body.path("data").path("operationId").asText()).isEqualTo(operation);
            assertThat(store.findUser("reset_target").authVersion()).isEqualTo(before.authVersion() + 1);
            clock.at(START.plusSeconds(61));
            first.login("reset_target", oldPassword, 401);
            first.login("reset_target", replacement, 200);
            assertThat(first.body.path("data").path("passwordChangeRequired").asBoolean()).isTrue();
            first.password(replacement, NEW_PASSWORD);
            first.login("reset_target", NEW_PASSWORD, 200);
            first.csrf();
            first.post(path, Map.of(), "denied-reset-0001", 403);
            admin.post("/api/admin/users/" + UUID.randomUUID() + "/password-reset", Map.of(), "missing-reset-0001", 404);
            admin.post("/api/admin/users/not-a-uuid/password-reset", Map.of(), "invalid-reset-0001", 400);
        }
    }

    @Test
    void selfResetClearsCurrentCookieAndRequiresNewLogin() throws Exception {
        try (Browser admin = administrator(); Browser second = new Browser()) {
            second.login("admin_test", NEW_PASSWORD, 200);
            String id = store.findUser("admin_test").id();
            admin.post("/api/admin/users/" + id + "/password-reset", Map.of(), "self-reset-0001", 200);
            assertThat(admin.body.path("data").path("currentSessionRevoked").asBoolean()).isTrue();
            assertThat(admin.cookies).doesNotContainKey(IdentityHttp.SESSION_COOKIE);
            second.get("/api/auth/session", 401);
            admin.get("/api/auth/session", 401);
        }
    }

    @Test
    void concurrentResetsShareOneReceiptAndFailedResetRollsBack() throws Exception {
        try (Browser admin = administrator(); Browser user = new Browser()) {
            admin.post("/api/admin/users", Map.of("username", "reset_race"), "create-race-0001", 201);
            String target = admin.body.path("data").path("userId").asText();
            String password = admin.body.path("data").path("temporaryPassword").asText();
            user.login("reset_race", password, 200);
            String sessionHash = IdentitySecrets.digest(admin.cookies.get(IdentityHttp.SESSION_COOKIE));
            UserEntity before = store.findUser("reset_race");
            String userSession = IdentitySecrets.digest(user.cookies.get(IdentityHttp.SESSION_COOKIE));
            assertThat(store.authorization(userSession).authVersion()).isEqualTo(before.authVersion());
            notifications.events.clear();
            String encoded = secrets.hash(NEW_PASSWORD);
            String fingerprint = secrets.fingerprint("RESET_PASSWORD", target);
            assertThatThrownBy(() -> store.resetPassword(sessionHash, target, NEW_PASSWORD, encoded,
                "x".repeat(129), fingerprint)).isInstanceOf(RuntimeException.class);
            assertThat(store.findUser("reset_race")).isEqualTo(before);
            assertThat(notifications.events).isEmpty();
            notifications.fail = true;
            user.get("/api/auth/session", 200);
            CompletableFuture<PasswordResetResponse> first = CompletableFuture.supplyAsync(() -> store.resetPassword(
                sessionHash, target, NEW_PASSWORD, encoded, "reset-race-0001", fingerprint));
            CompletableFuture<PasswordResetResponse> second = CompletableFuture.supplyAsync(() -> store.resetPassword(
                sessionHash, target, NEW_PASSWORD, encoded, "reset-race-0001", fingerprint));
            assertThat(first.join().credentialDelivered() ^ second.join().credentialDelivered()).isTrue();
            assertThat(first.join().operationId()).isEqualTo(second.join().operationId());
            assertThat(notifications.events).hasSize(1);
            assertThat(notifications.events.getFirst().userId()).isEqualTo(target);
            assertThat(notifications.events.getFirst().sessionHash()).isNull();
            assertThatThrownBy(() -> store.authorization(userSession)).isInstanceOf(IdentityFailure.class)
                .hasMessage("UNAUTHENTICATED");
            assertThat(store.findUser("reset_race").authVersion()).isEqualTo(before.authVersion() + 1);
            user.get("/api/auth/session", 401);
            assertThatThrownBy(() -> store.login(before, IdentitySecrets.digest(secrets.token()), secrets.token(), null, null, null))
                .isInstanceOf(IdentityFailure.class).hasMessage("INVALID_CREDENTIALS");
            CompletableFuture<PasswordResetResponse> next = CompletableFuture.supplyAsync(() -> store.resetPassword(
                sessionHash, target, NEW_PASSWORD, encoded, "reset-next-0001", fingerprint));
            CompletableFuture<PasswordResetResponse> last = CompletableFuture.supplyAsync(() -> store.resetPassword(
                sessionHash, target, NEW_PASSWORD, encoded, "reset-next-0002", fingerprint));
            assertThat(next.join().credentialDelivered() && last.join().credentialDelivered()).isTrue();
            assertThat(store.findUser("reset_race").authVersion()).isEqualTo(before.authVersion() + 3);
            assertThat(notifications.events).hasSize(3);
        }
    }

    private Browser administrator() throws Exception {
        Browser browser = new Browser();
        try {
            browser.login("admin_test", PASSWORD, 200);
            browser.password(PASSWORD, NEW_PASSWORD);
            browser.login("admin_test", NEW_PASSWORD, 200);
            browser.csrf();
            return browser;
        } catch (Exception | AssertionError failure) {
            browser.close();
            throw failure;
        }
    }

    private final class Browser implements AutoCloseable {
        final HttpClient client = HttpClient.newHttpClient();
        final Map<String, String> cookies = new HashMap<>();
        String token;
        String origin = "http://localhost:5173";
        JsonNode body;
        HttpResponse<String> last;

        void csrf() throws Exception {
            get("/api/auth/csrf", 200);
            token = body.path("data").path("token").asText();
            assertThat(token).hasSize(43);
        }
        void login(String username, String password, int status) throws Exception {
            csrf();
            post("/api/auth/login", Map.of("username", username, "password", password), null, status);
        }
        void password(String oldPassword, String newPassword) throws Exception {
            csrf();
            post("/api/auth/password", Map.of("currentPassword", oldPassword, "newPassword", newPassword),
                UUID.randomUUID().toString(), 204);
        }
        void get(String path, int status) throws Exception { send(path, null, null, status); }
        void post(String path, Object input, String key, int status) throws Exception { send(path, input, key, status); }
        void send(String path, Object input, String key, int status) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
            if (!cookies.isEmpty()) request.header("Cookie", String.join("; ", cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList()));
            if (input != null) {
                request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
                if (origin != null) request.header("Origin", origin);
                if (token != null) request.header(IdentityHttp.CSRF_HEADER, token);
                if (key != null) request.header("Idempotency-Key", key);
            }
            last = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String cookie : last.headers().allValues("set-cookie")) {
                String[] pair = cookie.split(";", 2)[0].split("=", 2);
                if (pair[1].isEmpty()) cookies.remove(pair[0]); else cookies.put(pair[0], pair[1]);
            }
            // Do not print credential response bodies, even on assertion failures.
            assertThat(last.statusCode()).as("HTTP status for %s", path).isEqualTo(status);
            assertThat(last.headers().firstValue("cache-control")).contains("no-store");
            body = last.body().isEmpty() ? json.nullNode() : json.readTree(last.body());
        }
        @Override public void close() { client.close(); }
    }
}
