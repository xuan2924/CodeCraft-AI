package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.infrastructure.ratelimit.SlidingWindowRateLimiter;

import ai.codecraft.modules.platformagent.service.PlatformBindingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.repository.ExerciseModelRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.ProblemTransactionService;
import ai.codecraft.modules.workspace.service.StorageCapacityStore;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import ai.codecraft.modules.identity.repository.RevocationIntentRepository;
import ai.codecraft.modules.identity.service.IdentityRevocationBarrier;
import ai.codecraft.modules.identity.service.IdentityRevocationNotifier;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.config.ModelCheckProperties;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.config.ModelProbeProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.gateway.ModelSdkClientFactory;
import ai.codecraft.modules.modelaccess.model.dto.ModelCallResultDTO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.concurrent.atomic.AtomicInteger;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckResponse;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckEntity;
import java.time.Duration;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCredentialRotationCandidateDTO;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.Configuration;
import java.sql.Timestamp;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable PostgreSQL/MyBatis seam; paid provider work requires the explicit live-test environment flag. */
@EnabledIfEnvironmentVariable(named = "MODEL_ACCESS_IT_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelConnectionPersistenceIT {
    private static final Instant NOW = "1".equals(System.getenv("MODEL_ACCESS_LIVE_DEEPSEEK")) ? Instant.now() : Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ModelConnectionRequest input = new ModelConnectionRequest("DeepSeek", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT,
        ModelProviderPreset.DEEPSEEK, "fixture-not-a-real-model-key", null);
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transactions;
    private ModelConnectionService service;
    private ModelConfigurationService models;
    private ModelCheckService checks;
    private ModelCheckService checksAfterValidity;
    private ModelCheckExecutionStore dispatches;
    private ModelCheckExecutionStore expiredDispatches;
    private ModelCredentialRotationStore rotation;
    private ModelCheckRetentionService retention;
    private ModelCredentialCipher rotatedCipher;
    private String owner;
    private ExerciseModelService exerciseModels;
    private ExerciseTransactionService exerciseLifecycle;

    @BeforeAll
    void migrate() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("MODEL_ACCESS_IT_JDBC_URL"), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source); transactions = new TransactionTemplate(manager);
        var configuration = new Configuration();
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(IdentityRepository.class); configuration.addMapper(ModelConnectionRepository.class); configuration.addMapper(ModelConfigurationRepository.class);
        configuration.addMapper(ModelCheckRepository.class);
        configuration.addMapper(ExerciseModelRepository.class);
        configuration.addMapper(ExerciseRepository.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void seed() {
        owner = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES (?::uuid,?,'fixture','USER',false)", owner, "u_" + owner.substring(0, 20));
        var identity = mock(IdentityTransactionService.class);
        var locks = transactional(new IdentityTransactionService(sql.getMapper(IdentityRepository.class), clock, mock(IdentityRevocationNotifier.class),
            mock(RevocationIntentRepository.class), mock(IdentityRevocationBarrier.class), mock(SlidingWindowRateLimiter.class)), IdentityTransactionService.class);
        doAnswer(call -> { locks.lockResourceOwner(call.getArgument(0)); return null; }).when(identity).lockResourceOwner(anyString());
        var grant = new SessionAuthorizationDTO(owner, "USER", false, 1, NOW.plusSeconds(3600));
        when(identity.authorization("session")).thenReturn(grant);
        when(identity.authorizeMutation("session")).thenAnswer(call -> { locks.lockResourceOwner(owner); return grant; });
        when(identity.tryAuthorizeMutation("session")).thenAnswer(call -> { locks.lockResourceOwner(owner); return grant; });
        when(identity.authorizePrivateDelivery("session")).thenAnswer(call -> { locks.lockResourceOwner(owner); return grant; });
        var secrets = mock(IdentitySecrets.class);
        when(secrets.fingerprint(anyString())).thenAnswer(call -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(call.<String>getArgument(0).getBytes(StandardCharsets.UTF_8))));
        byte[] fixtureKey = new byte[32];
        if ("1".equals(System.getenv("MODEL_ACCESS_LIVE_DEEPSEEK"))) new SecureRandom().nextBytes(fixtureKey);
        var cipher = new ModelCredentialCipher(new ModelSecurityProperties("fixture", "fixture:" + Base64.getEncoder().encodeToString(fixtureKey)));
        var repository = sql.getMapper(ModelConnectionRepository.class);
        rotatedCipher = new ModelCredentialCipher(new ModelSecurityProperties("rotated", "fixture:" + Base64.getEncoder().encodeToString(fixtureKey)
            + ",rotated:" + Base64.getEncoder().encodeToString("n".repeat(32).getBytes(StandardCharsets.US_ASCII))));
        rotation = transactional(new ModelCredentialRotationStore(identity, repository, rotatedCipher), ModelCredentialRotationStore.class);
        retention = transactional(new ModelCheckRetentionService(sql.getMapper(ModelCheckRepository.class), identity, clock), ModelCheckRetentionService.class);
        var receipts = new ModelCommandReceipts(repository, secrets, new ObjectMapper().findAndRegisterModules(), clock);
        service = transactional(new ModelConnectionService(repository, identity, cipher, new ModelConnectionValidator(), receipts, clock), ModelConnectionService.class);
        models = transactional(new ModelConfigurationService(sql.getMapper(ModelConfigurationRepository.class), repository, identity,
            new ModelConnectionValidator(), receipts, clock), ModelConfigurationService.class);
        checks = transactional(new ModelCheckService(sql.getMapper(ModelCheckRepository.class), sql.getMapper(ModelConfigurationRepository.class), repository,
            identity, receipts, clock, new ModelCheckProperties(Duration.ofMinutes(5), 3, 65536)), ModelCheckService.class);
        checksAfterValidity = transactional(new ModelCheckService(sql.getMapper(ModelCheckRepository.class), sql.getMapper(ModelConfigurationRepository.class), repository,
            identity, receipts, Clock.offset(clock, Duration.ofMinutes(30)), new ModelCheckProperties(Duration.ofMinutes(5), 3, 65536)), ModelCheckService.class);
        var invocations = transactional(new ModelInvocationService(identity, repository, sql.getMapper(ModelConfigurationRepository.class), cipher), ModelInvocationService.class);
        exerciseModels = transactional(new ExerciseModelService(sql.getMapper(ExerciseModelRepository.class), sql.getMapper(ExerciseRepository.class), identity,
            mock(WorkspaceCoordinationStore.class), invocations, checks, clock, secrets), ExerciseModelService.class);
        exerciseLifecycle = transactional(new ExerciseTransactionService(sql.getMapper(ExerciseRepository.class), identity, mock(ProblemTransactionService.class), clock, secrets,
            mock(WorkspaceCoordinationStore.class), new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), mock(StorageCapacityStore.class), exerciseModels, mock(PlatformBindingService.class)), ExerciseTransactionService.class);
        dispatches = transactional(new ModelCheckExecutionStore(sql.getMapper(ModelCheckRepository.class), identity, invocations,
            new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 8, 8388608, 65536, 262144),
            new ModelCheckProperties(Duration.ofMinutes(5), 3, 65536), clock,
            new ModelUsageAccounting(new ModelUsageNormalizer(), new ModelPriceCatalog(), new ModelCostCalculator())), ModelCheckExecutionStore.class);
        expiredDispatches = transactional(new ModelCheckExecutionStore(sql.getMapper(ModelCheckRepository.class), identity, invocations,
            new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 8, 8388608, 65536, 262144),
            new ModelCheckProperties(Duration.ofMinutes(5), 3, 65536), Clock.offset(clock, Duration.ofSeconds(120)),
            new ModelUsageAccounting(new ModelUsageNormalizer(), new ModelPriceCatalog(), new ModelCostCalculator())), ModelCheckExecutionStore.class);
    }

    @Test
    void failedCheckKeepsReportedUsageOnceWithoutPricingItFromTodaysCatalog() {
        var connection = service.create("session", "usage-connection", input);
        var model = models.create("session", connection.id(), "usage-model", model("Fixture", "deepseek-flash", null));
        var check = checks.start("session", model.id(), "usage-check", true);
        String worker = UUID.randomUUID().toString(); var call = dispatches.begin(check.id(), 1, worker);
        var reported = OpenAiTokenUsage.builder().inputTokenCount(1000).outputTokenCount(50).totalTokenCount(1050)
            .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(600).cacheWriteTokens(100).build()).build();
        assertThat(dispatches.recordUsage(call.requestId(), UUID.randomUUID().toString(), reported)).isFalse();
        assertThat(dispatches.recordUsage(call.requestId(), worker, reported)).isTrue();
        assertThat(dispatches.recordUsage(call.requestId(), worker, reported)).isFalse();
        assertThat(dispatches.complete(call.requestId(), worker, 100, ModelAccessErrorCode.MODEL_INVALID_RESPONSE)).isFalse();
        var view = checks.usage("session", check.id());
        assertThat(view.totals()).singleElement().satisfies(total -> {
            assertThat(total.calls()).isEqualTo("1"); assertThat(total.completeCalls()).isEqualTo("1");
            assertThat(total.inputTokens()).isEqualTo("1000"); assertThat(total.ordinaryInputTokens()).isEqualTo("300");
            assertThat(total.totalTokens()).isEqualTo("1050"); assertThat(total.pricedCalls()).isEqualTo("0");
        });
        assertThat(view.costs()).isEmpty();
        assertThat(checks.usage("session", check.id())).isEqualTo(view);
        assertThat(jdbc.queryForObject("SELECT configuration_payload FROM personal_model_check_request WHERE id=?::uuid", String.class, call.requestId()))
            .contains(connection.id(), model.id()).doesNotContain("fixture-not-a-real-model-key");
        assertThatThrownBy(() -> checks.usage("session", UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
    }

    @Test
    void exerciseSelectionRequiresChecksOnlyAtStartAndDoesNotFollowEditsOrReplayOldSelection() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", model("Fixture", "fixture", null));
        var second = models.create("session", connection.id(), "second", model("Second", "second", null));
        String exercise = UUID.randomUUID().toString();
        sql.getMapper(ExerciseRepository.class).insert(new ExerciseEntity(exercise, owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), "READY", null, "[]", 1, NOW, null, null, null, null, null));
        var prepared = sql.getMapper(ExerciseRepository.class).find(exercise);
        sql.getMapper(ExerciseRepository.class).prepared(exercise, prepared.workspaceInstanceId(), prepared.preparationOperationId(), 1, "[]");
        var firstReference = new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1);
        var secondReference = new ModelInvocationReferenceDTO(connection.id(), second.id(), 1, 1);
        assertThatThrownBy(() -> exerciseModels.requireReady("session", exercise)).isInstanceOf(BusinessException.class);
        exerciseModels.select("session", exercise, "fixture-control", "first", firstReference);
        assertThatThrownBy(() -> exerciseLifecycle.start("session", exercise, "start", 600, "fixture-control")).isInstanceOf(BusinessException.class);
        assertThat(exerciseLifecycle.find("session", exercise).state()).isEqualTo("READY");
        assertThatThrownBy(() -> exerciseModels.requireReady("session", exercise)).isInstanceOf(BusinessException.class);
        var check = checks.start("session", model.id(), "check", true);
        String worker = UUID.randomUUID().toString();
        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            var request = dispatches.begin(check.id(), ordinal, worker);
            assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isTrue();
        }
        exerciseModels.requireReady("session", exercise);
        assertThat(exerciseLifecycle.start("session", exercise, "start", 600, "fixture-control").state()).isEqualTo("ACTIVE");
        exerciseModels.select("session", exercise, "fixture-control", "second", secondReference);
        exerciseModels.select("session", exercise, "fixture-control", "first", firstReference);
        assertThat(exerciseModels.current("session", exercise).selected()).isEqualTo(secondReference);
        assertThatThrownBy(() -> exerciseModels.select("session", exercise, "fixture-control", "first", secondReference)).isInstanceOf(BusinessException.class);
        service.update("session", connection.id(), "update-key", new ModelConnectionRequest(input.name(), input.baseUrl(), input.protocol(), input.preset(), "new-fixture-key", connection.version()));
        assertThat(exerciseModels.current("session", exercise).availability()).isEqualTo("MODEL_VERSION_CONFLICT");
        assertThatThrownBy(() -> exerciseModels.requireReady("session", exercise)).isInstanceOf(BusinessException.class);
        models.delete("session", second.id(), "delete", second.version());
        assertThat(exerciseModels.current("session", exercise).availability()).isEqualTo("MODEL_CONFIGURATION_NOT_FOUND");
        assertThat(exerciseLifecycle.start("session", exercise, "start", 600, "fixture-control").deadlineAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void terminalRetentionStartsAtTransitionRatherThanTransactionStart() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", model("Fixture", "fixture", null));
        var original = checks.start("session", model.id(), "original", true);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
            var earliest = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
            assertThat(sql.getMapper(ModelCheckRepository.class).transition(original.id(), "FAILED", "fixture")).isEqualTo(1);
            var completed = jdbc.queryForObject("SELECT completed_at FROM personal_model_check WHERE id=?::uuid", Timestamp.class, original.id());
            assertThat(completed.toInstant()).isAfterOrEqualTo(earliest.toInstant());
            var cached = new ModelCheckEntity(UUID.randomUUID().toString(), owner, connection.id(), model.id(), 1, 1, "SUCCEEDED", NOW);
            sql.getMapper(ModelCheckRepository.class).insert(cached);
            var cachedCompleted = jdbc.queryForObject("SELECT completed_at FROM personal_model_check WHERE id=?::uuid", Timestamp.class, cached.id());
            assertThat(cachedCompleted.toInstant()).isAfterOrEqualTo(earliest.toInstant());
        });
    }

    @Test
    void retentionProtectsReferencesAndDeletesChildrenWithoutReopeningCommandIdentity() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", model("Fixture", "fixture", null));
        var original = checks.start("session", model.id(), "original", true);
        var worker = UUID.randomUUID().toString();
        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            var request = dispatches.begin(original.id(), ordinal, worker);
            assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isTrue();
        }
        var reused = checks.start("session", model.id(), "reused", true);
        var cutoff = NOW.minus(Duration.ofDays(30));
        jdbc.update("UPDATE personal_model_check SET completed_at=? WHERE id=?::uuid", Timestamp.from(cutoff), original.id());
        assertThat(retention.deleteExpired(original.id())).isFalse();
        assertThat(checks.status("session", reused.id()).basic().valid()).isTrue();
        jdbc.update("UPDATE personal_model_check SET completed_at=? WHERE id=?::uuid", Timestamp.from(cutoff.plusSeconds(1)), reused.id());
        assertThat(retention.deleteExpired(reused.id())).isFalse();
        jdbc.update("UPDATE personal_model_check SET completed_at=? WHERE id=?::uuid", Timestamp.from(cutoff), reused.id());
        assertThat(sql.getMapper(ModelCheckRepository.class).retentionCandidates(cutoff)).contains(reused.id()).doesNotContain(original.id());
        assertThatThrownBy(() -> transactions.execute(status -> {
            assertThat(retention.deleteExpired(reused.id())).isTrue();
            throw new IllegalStateException("injected cleanup rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("injected cleanup rollback");
        assertThat(checks.status("session", reused.id()).basic().valid()).isTrue();
        assertThat(retention.deleteExpired(original.id())).isFalse();
        assertThat(retention.deleteExpired(reused.id())).isTrue();
        assertThat(retention.deleteExpired(original.id())).isTrue();
        assertThat(retention.deleteExpired(original.id())).isFalse();
        assertThatThrownBy(() -> checks.status("session", original.id())).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_CHECK_NOT_FOUND"));
        assertThat(checks.start("session", model.id(), "original", true)).isEqualTo(original);
        for (var table : List.of("personal_model_check_request", "personal_model_check_reuse", "personal_model_check_authority")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE check_id IN (?::uuid,?::uuid)", Integer.class, original.id(), reused.id())).isZero();
        }
        var active = checks.start("session", model.id(), "active", true);
        jdbc.update("UPDATE personal_model_check SET completed_at=? WHERE id=?::uuid", Timestamp.from(cutoff), active.id());
        assertThat(retention.deleteExpired(active.id())).isFalse();
    }

    @Test
    void deploymentRotationChangesOnlyWrappingMaterialAndCannotReviveDeletedCredentials() {
        var connection = service.create("session", "connection", input);
        var repository = sql.getMapper(ModelConnectionRepository.class);
        var before = repository.find(connection.id());
        assertThat(rotation.rotate(owner, connection.id())).isTrue();
        var after = repository.find(connection.id());
        assertThat(service.connection("session", connection.id())).isEqualTo(connection);
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(after.credentialKeyId()).isEqualTo("rotated");
        assertThat(rotatedCipher.decrypt(owner, connection.id(), after.credential())).isEqualTo(input.apiKey());
        assertThat(repository.rotationCandidates("fixture", null)).anyMatch(candidate -> candidate.id().equals(connection.id()) && candidate.userId().equals(owner));
        assertThat(rotation.rotate(owner, connection.id())).isFalse();
        assertThat(repository.rotateCredential(connection.id(), owner, before.credential(), before.credential())).isZero();
        service.delete("session", connection.id(), "delete", connection.version());
        assertThat(rotation.rotate(owner, connection.id())).isFalse();
        assertThat(repository.find(connection.id()).credentialCiphertext()).isNull();
    }

    @Test
    void replacementAfterDiscoveryIsRewrappedWithoutRestoringTheEarlierProviderKey() {
        var connection = service.create("session", "connection", input);
        var discovered = new ModelCredentialRotationCandidateDTO(connection.id(), owner);
        var updated = service.update("session", connection.id(), "replacement", new ModelConnectionRequest(input.name(), input.baseUrl(), input.protocol(),
            input.preset(), "fixture-latest-provider-key", connection.version()));
        assertThat(rotation.rotate(discovered.userId(), discovered.id())).isTrue();
        var current = sql.getMapper(ModelConnectionRepository.class).find(connection.id());
        assertThat(rotatedCipher.decrypt(owner, connection.id(), current.credential())).isEqualTo("fixture-latest-provider-key");
        assertThat(service.connection("session", connection.id())).isEqualTo(updated);
        assertThat(service.create("session", "connection", input)).isEqualTo(connection);
        assertThat(service.connection("session", connection.id())).isEqualTo(updated);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MODEL_ACCESS_LIVE_DEEPSEEK", matches = "1")
    void realDeepSeekBasicAndSideEffectFreeToolChecksPassAndReuseTheirOriginalResults() {
        realChecks(ModelProtocol.OPENAI_CHAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI_RESPONSES", "ANTHROPIC"})
    @EnabledIfEnvironmentVariable(named = "MODEL_ACCESS_LIVE_DEEPSEEK", matches = "1")
    void realDeepSeekAlternateProtocolChecksPassAndReuse(String protocol) {
        realChecks(ModelProtocol.valueOf(protocol));
    }

    private void realChecks(ModelProtocol protocol) {
        String key = System.getenv("DeepSeek");
        assertThat(key != null && !key.isBlank()).as("DeepSeek credential must be supplied through the environment").isTrue();
        var connection = service.create("session", "live-connection", new ModelConnectionRequest("Live fixture", protocol == ModelProtocol.ANTHROPIC ? "https://api.deepseek.com/anthropic/v1" : "https://api.deepseek.com", protocol, ModelProviderPreset.DEEPSEEK, key, null));
        var model = models.create("session", connection.id(), "live-model", new ModelConfigurationRequest("Live fixture", "deepseek-flash", 100000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var limits = new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 3, 8388608, 65536, 262144);
        try (var driver = new ModelCallDriver(new ModelSdkClientFactory(new ModelConnectionValidator(), limits), limits, clock)) {
            var runner = new ModelCheckRunner(dispatches, driver, new ModelCheckPrompts(), new ModelProbeProperties(128, 256, 2048), limits, new ObjectMapper());
            var admitted = checks.start("session", model.id(), "live-admit", true);
            runner.run(admitted.id());
            var complete = checks.latest("session", model.id());
            var samples = jdbc.queryForList("SELECT ordinal,usage_payload,usage_facts,estimated_cost,cost_currency,price_reason FROM personal_model_check_request WHERE check_id=?::uuid ORDER BY ordinal", admitted.id());
            System.out.println("DeepSeek " + protocol + " check usage sample (numeric evidence only): " + samples);
            assertThat(samples).hasSizeLessThanOrEqualTo(3);
            assertThat(checks.usage("session", admitted.id()).totals()).singleElement().satisfies(total -> {
                assertThat(total.calls()).isEqualTo("3");
                assertThat(total.inputTokens()).isNotNull();
                assertThat(total.outputTokens()).isNotNull();
            });
            assertThat(complete.basic().valid()).as("basic check code: %s", complete.basic().errorCode()).isTrue();
            assertThat(complete.tool().valid()).as("tool check code: %s", complete.tool().errorCode()).isTrue();
            assertThat(sql.getMapper(ModelCheckRepository.class).results(admitted.id())).hasSize(3);
            var reused = checks.start("session", model.id(), "live-reuse", true);
            runner.run(reused.id());
            assertThat(checks.status("session", reused.id()).basic().reused()).isTrue();
            assertThat(checks.status("session", reused.id()).tool().reused()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_check_request WHERE check_id=?::uuid", Integer.class, reused.id())).isZero();
        } finally {
            service.delete("session", connection.id(), "live-cleanup", connection.version());
        }
    }

    @Test
    void admittedGroupRunsThroughRealTransactionsAndSubsequentReuseMakesNoProviderCalls() throws Exception {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var driver = mock(ModelCallDriver.class);
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            int ordinal = count.incrementAndGet();
            ChatRequest request = call.getArgument(1);
            String prompt = ((UserMessage) request.messages().get(0)).singleText();
            AiMessage response;
            if (ordinal == 1) response = AiMessage.from(prompt.substring(prompt.lastIndexOf('\n', prompt.length() - 2) + 1).strip());
            else if (ordinal == 2) response = AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("fixture-tool").name("connection_probe")
                .arguments("{\"nonce\":\"" + prompt.split("\"")[1] + "\"}").build())).build();
            else response = AiMessage.from(((ToolExecutionResultMessage) request.messages().get(2)).text());
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(response).build(), 100);
        });
        var runner = new ModelCheckRunner(dispatches, driver, new ModelCheckPrompts(), new ModelProbeProperties(128, 256, 2048),
            new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 8, 8388608, 65536, 262144), new ObjectMapper());
        var admitted = checks.start("session", model.id(), "admit", true);
        assertThat(sql.getMapper(ModelCheckRepository.class).queued()).contains(admitted.id());
        runner.run(admitted.id());
        var complete = checks.latest("session", model.id());
        assertThat(complete.check().id()).isEqualTo(admitted.id());
        assertThat(complete.basic().valid()).isTrue(); assertThat(complete.tool().valid()).isTrue();
        var reused = checks.start("session", model.id(), "reuse", true);
        runner.run(reused.id());
        assertThat(sql.getMapper(ModelCheckRepository.class).queued()).doesNotContain(admitted.id(), reused.id());
        verify(driver, times(3)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void successfulChecksReuseOriginalResultsWithoutExtendingTheirThirtyMinuteValidity() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var original = checks.start("session", model.id(), "original", true);
        String worker = UUID.randomUUID().toString();
        String basic = null;
        String tool = null;
        for (int step = 1; step <= 3; step++) {
            var request = dispatches.begin(original.id(), step, worker);
            assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isTrue();
            if (step == 1) basic = request.requestId();
            if (step == 3) tool = request.requestId();
        }
        var reused = checks.start("session", model.id(), "reuse", true);
        assertThat(reused.status()).isEqualTo("SUCCEEDED");
        var details = checks.status("session", reused.id());
        assertThat(details.basic().reused()).isTrue(); assertThat(details.tool().reused()).isTrue();
        assertThat(details.basic().valid()).isTrue(); assertThat(details.tool().valid()).isTrue();
        assertThat(details.basic().completedAt()).isEqualTo(NOW);
        assertThat(details.basic().validUntil()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(checksAfterValidity.status("session", reused.id()).basic().valid()).isFalse();
        assertThat(sql.getMapper(ModelCheckRepository.class).reuse(reused.id()).basicRequestId()).isEqualTo(basic);
        assertThat(sql.getMapper(ModelCheckRepository.class).reuse(reused.id()).toolRequestId()).isEqualTo(tool);
        assertThat(dispatches.begin(reused.id(), 1, UUID.randomUUID().toString())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_check_request WHERE check_id=?::uuid", Integer.class, reused.id())).isZero();
        var expired = checksAfterValidity.start("session", model.id(), "expired", true);
        assertThat(expired.status()).isEqualTo("QUEUED");
        assertThat(dispatches.firstOrdinal(expired.id())).isEqualTo(1);
        assertThat(sql.getMapper(ModelCheckRepository.class).reuse(expired.id())).isNull();
    }

    @Test
    void basicSuccessSurvivesToolFailureAndOnlyTheToolRoundtripIsChargedOnRetry() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var original = checks.start("session", model.id(), "original", true);
        String worker = UUID.randomUUID().toString();
        var basic = dispatches.begin(original.id(), 1, worker);
        assertThat(dispatches.complete(basic.requestId(), worker, 100, null)).isTrue();
        var failed = dispatches.begin(original.id(), 2, worker);
        assertThat(dispatches.complete(failed.requestId(), worker, 100, ModelAccessErrorCode.MODEL_INVALID_RESPONSE)).isFalse();
        var retry = checks.start("session", model.id(), "retry", true);
        assertThat(dispatches.firstOrdinal(retry.id())).isEqualTo(2);
        String nextWorker = UUID.randomUUID().toString();
        assertThat(dispatches.begin(retry.id(), 1, nextWorker)).isNull();
        for (int step = 2; step <= 3; step++) {
            var request = dispatches.begin(retry.id(), step, nextWorker);
            assertThat(dispatches.complete(request.requestId(), nextWorker, 100, null)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_check_request WHERE check_id=?::uuid", Integer.class, retry.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM personal_model_check WHERE id=?::uuid", String.class, retry.id())).isEqualTo("SUCCEEDED");
    }

    @Test
    void stillValidToolResultNeedsOnlyOneNewRequestWhenBasicHasExpired() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var original = checks.start("session", model.id(), "original", true);
        String worker = UUID.randomUUID().toString();
        for (int step = 1; step <= 3; step++) {
            var request = dispatches.begin(original.id(), step, worker);
            assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isTrue();
        }
        jdbc.update("UPDATE personal_model_check_request SET completed_at=? WHERE check_id=?::uuid AND ordinal=1", Timestamp.from(NOW.minusSeconds(1800)), original.id());
        var retry = checks.start("session", model.id(), "retry", true);
        String nextWorker = UUID.randomUUID().toString();
        assertThat(dispatches.firstOrdinal(retry.id())).isEqualTo(1);
        var basic = dispatches.begin(retry.id(), 1, nextWorker);
        assertThat(dispatches.complete(basic.requestId(), nextWorker, 100, null)).isTrue();
        assertThat(dispatches.begin(retry.id(), 2, nextWorker)).isNull();
        var details = checks.status("session", retry.id());
        assertThat(details.check().status()).isEqualTo("SUCCEEDED");
        assertThat(details.tool().reused()).isTrue(); assertThat(details.tool().completedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_check_request WHERE check_id=?::uuid", Integer.class, retry.id())).isEqualTo(1);
    }

    @Test
    void displayRenamesRetainReusableResultsButCredentialChangesInvalidateThem() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var original = checks.start("session", model.id(), "original", true);
        String worker = UUID.randomUUID().toString();
        for (int step = 1; step <= 3; step++) {
            var request = dispatches.begin(original.id(), step, worker);
            assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isTrue();
        }
        models.update("session", model.id(), "rename", new ModelConfigurationRequest("Renamed", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, "1"));
        var renamed = checks.start("session", model.id(), "rename-check", true);
        assertThat(renamed.status()).isEqualTo("SUCCEEDED");
        service.update("session", connection.id(), "key-change", new ModelConnectionRequest("DeepSeek", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, "fixture-replacement-key", "1"));
        assertThat(checks.status("session", renamed.id()).currentConfiguration()).isFalse();
        assertThat(checks.status("session", renamed.id()).basic().valid()).isFalse();
        var changed = checks.start("session", model.id(), "changed-check", true);
        assertThat(changed.status()).isEqualTo("QUEUED");
        assertThat(sql.getMapper(ModelCheckRepository.class).reuse(changed.id())).isNull();
    }

    @Test
    void competingWorkersCannotBothAcquireTheSamePaidRequest() throws Exception {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var check = checks.start("session", model.id(), "check", true);
        var results = race(() -> dispatches.begin(check.id(), 1, UUID.randomUUID().toString()) != null,
            () -> dispatches.begin(check.id(), 1, UUID.randomUUID().toString()) != null);
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_check_request WHERE check_id=?::uuid", Integer.class, check.id())).isEqualTo(1);
    }

    @Test
    void dispatchRollbackDoesNotConsumeTheRequestOrWorkerClaim() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var check = checks.start("session", model.id(), "check", true);
        assertThatThrownBy(() -> transactions.execute(status -> {
            dispatches.begin(check.id(), 1, UUID.randomUUID().toString()); throw new IllegalStateException("injected rollback");
        })).hasMessage("injected rollback");
        assertThat(dispatches.begin(check.id(), 1, UUID.randomUUID().toString())).isNotNull();
    }

    @Test
    void expiredContinuationAndRecoveryConvergeInEitherTransactionOrder() {
        var connection = service.create("session", "connection", input);
        for (boolean recoveryFirst : new boolean[] {false, true}) {
            var model = models.create("session", connection.id(), "model-" + recoveryFirst, new ModelConfigurationRequest("Fixture-" + recoveryFirst, "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
            var check = checks.start("session", model.id(), "check-" + recoveryFirst, true);
            String worker = UUID.randomUUID().toString();
            var first = dispatches.begin(check.id(), 1, worker);
            assertThat(dispatches.complete(first.requestId(), worker, 100, null)).isTrue();
            if (recoveryFirst) assertThat(expiredDispatches.recoverExpired(check.id())).isTrue();
            assertThat(expiredDispatches.begin(check.id(), 2, worker)).isNull();
            assertThat(expiredDispatches.recoverExpired(check.id())).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM personal_model_check WHERE id=?::uuid", String.class, check.id())).isEqualTo("INTERRUPTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_check_request WHERE check_id=?::uuid", Integer.class, check.id())).isEqualTo(1);
        }
    }

    @Test
    void completedThreeRequestGroupCannotBeRepeatedAndCountsDecodedBytesAcrossPhases() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var check = checks.start("session", model.id(), "check", true);
        String worker = UUID.randomUUID().toString();
        for (int step = 1; step <= 3; step++) {
            var request = dispatches.begin(check.id(), step, worker);
            assertThat(request.remainingDecodedBytes()).isEqualTo(65536 - (step - 1) * 100);
            assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isTrue();
            assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM personal_model_check WHERE id=?::uuid", String.class, check.id())).isEqualTo("SUCCEEDED");
        assertThat(dispatches.begin(check.id(), 3, worker)).isNull();
        assertThat(sql.getMapper(ModelCheckRepository.class).decodedBytes(check.id())).isEqualTo(300);
    }

    @Test
    void expiredRecoveryIsDurableAndLateCompletionCannotReopenTheGroup() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var check = checks.start("session", model.id(), "check", true);
        String worker = UUID.randomUUID().toString();
        var request = dispatches.begin(check.id(), 1, worker);
        assertThat(sql.getMapper(ModelCheckRepository.class).expired(NOW)).doesNotContain(check.id());
        assertThat(sql.getMapper(ModelCheckRepository.class).expired(NOW.plusSeconds(120))).contains(check.id());
        assertThat(dispatches.recoverExpired(check.id())).isFalse();
        assertThat(expiredDispatches.recoverExpired(check.id())).isTrue();
        assertThat(expiredDispatches.recoverExpired(check.id())).isFalse();
        assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isFalse();
        assertThat(sql.getMapper(ModelCheckRepository.class).request(request.requestId()).status()).isEqualTo("INTERRUPTED");
    }

    @Test
    void configurationEditedAfterDispatchCannotBeCertifiedByItsLateSuccess() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var check = checks.start("session", model.id(), "check", true);
        String worker = UUID.randomUUID().toString();
        var request = dispatches.begin(check.id(), 1, worker);
        service.delete("session", connection.id(), "delete", connection.version());
        assertThat(dispatches.complete(request.requestId(), worker, 100, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM personal_model_check WHERE id=?::uuid", String.class, check.id())).isEqualTo("SUPERSEDED");
    }

    @Test
    void durableDispatchIsSingleUseAndKeepsCredentialsOutOfTheLedger() throws Exception {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var check = checks.start("session", model.id(), "check", true);
        String worker = UUID.randomUUID().toString();
        var dispatched = dispatches.begin(check.id(), 1, worker);
        assertThat(dispatched.invocation().credential()).isEqualTo(input.apiKey());
        assertThat(dispatched.notAfter()).isEqualTo(NOW.plusSeconds(120));
        assertThat(dispatched.remainingDecodedBytes()).isEqualTo(65536);
        assertThat(dispatches.begin(check.id(), 1, worker)).isNull();
        assertThat(dispatches.begin(check.id(), 2, worker)).isNull();
        assertThat(jdbc.queryForObject("SELECT row_to_json(r)::text FROM personal_model_check_request r WHERE check_id=?::uuid", String.class, check.id())).doesNotContain(input.apiKey());
    }

    @Test
    void editBetweenAdmissionAndDispatchCommitsFailureWithoutRollbackPoisoning() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var check = checks.start("session", model.id(), "check", true);
        models.update("session", model.id(), "edit", new ModelConfigurationRequest("Fixture", "changed-model", 10000, 4096, ModelThinkingMode.OFF, null, null, null, model.version()));
        assertThat(dispatches.begin(check.id(), 1, UUID.randomUUID().toString())).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM personal_model_check WHERE id=?::uuid", String.class, check.id())).isEqualTo("FAILED");
        assertThat(checks.start("session", model.id(), "new-check", true).status()).isEqualTo("QUEUED");
    }

    @Test
    void concurrentCheckStartsAcrossModelsShareOneUserAdmission() throws Exception {
        var connection = service.create("session", "connection", input);
        var first = models.create("session", connection.id(), "first", new ModelConfigurationRequest("First", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var second = models.create("session", connection.id(), "second", new ModelConfigurationRequest("Second", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var outcomes = race(() -> checks.start("session", first.id(), "one", true), () -> checks.start("session", second.id(), "two", true));
        assertThat(outcomes.stream().filter(ModelCheckResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes).contains("MODEL_CHECK_ALREADY_ACTIVE");
    }

    @Test
    void sameCheckKeyReplaysAfterDeletionWithoutCountingAgain() throws Exception {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        var outcomes = race(() -> checks.start("session", model.id(), "same", true), () -> checks.start("session", model.id(), "same", true));
        assertThat(outcomes.get(0)).isInstanceOf(ModelCheckResponse.class).isEqualTo(outcomes.get(1));
        service.delete("session", connection.id(), "delete", connection.version());
        assertThat(checks.start("session", model.id(), "same", true)).isEqualTo(outcomes.get(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_check WHERE user_id=?::uuid", Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void failedAndInterruptedStartsCountUntilTheyLeaveTheWindow() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO personal_model_check(id,user_id,connection_id,model_configuration_id,connection_configuration_version,model_configuration_version,status,started_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,1,1,?,?)",
                UUID.randomUUID().toString(), owner, connection.id(), model.id(), i == 0 ? "INTERRUPTED" : "FAILED", Timestamp.from(NOW.minusSeconds(299)));
        }
        assertThatThrownBy(() -> checks.start("session", model.id(), "new", true)).isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_CHECK_RATE_LIMITED"));
        jdbc.update("UPDATE personal_model_check SET started_at=? WHERE user_id=?::uuid", Timestamp.from(NOW.minusSeconds(300)), owner);
        assertThat(checks.start("session", model.id(), "new", true).status()).isEqualTo("QUEUED");
    }

    @Test
    void checkAdmissionAndReceiptRollBackTogether() {
        var connection = service.create("session", "connection", input);
        var model = models.create("session", connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 10000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        assertThatThrownBy(() -> transactions.execute(status -> {
            checks.start("session", model.id(), "rollback", true); throw new IllegalStateException("injected rollback");
        })).hasMessage("injected rollback");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_check WHERE user_id=?::uuid", Integer.class, owner)).isZero();
        assertThat(checks.start("session", model.id(), "rollback", true).status()).isEqualTo("QUEUED");
    }

    @Test
    void ciphertextAndFullReceiptPersistWithoutPlaintextAndReplayIsStable() throws Exception {
        var created = service.create("session", "key", input);
        assertThat(service.create("session", "key", input)).isEqualTo(created);
        assertThat(service.connection("session", created.id())).isEqualTo(created);
        assertThat(new ObjectMapper().writeValueAsString(created)).doesNotContain(input.apiKey());
        assertThat(jdbc.queryForObject("SELECT credential_ciphertext FROM personal_model_connection WHERE id=?::uuid", String.class, created.id())).doesNotContain(input.apiKey());
        assertThat(jdbc.queryForObject("SELECT response::text FROM personal_model_command WHERE user_id=?::uuid", String.class, owner)).doesNotContain(input.apiKey());
        assertThat(service.connections("session", null).connections()).containsExactly(created);
    }

    @Test
    void competingCreateKeysForTheSameNameSerializeAtTheActualOwnerLock() throws Exception {
        var outcomes = race(() -> service.create("session", "one", input), () -> service.create("session", "two", input));
        assertThat(outcomes.stream().filter(ModelConnectionResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes).contains("MODEL_NAME_CONFLICT");
    }

    @Test
    void sameCreationKeyConvergesUnderActualConcurrentTransactions() throws Exception {
        var outcomes = race(() -> service.create("session", "same", input), () -> service.create("session", "same", input));
        assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_connection WHERE user_id=?::uuid", Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void rollbackCannotLeaveAConnectionOrAnAcceptedCommandBehind() {
        assertThatThrownBy(() -> transactions.execute(status -> {
            service.create("session", "rollback", input); throw new IllegalStateException("injected rollback");
        })).hasMessage("injected rollback");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_connection WHERE user_id=?::uuid", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_command WHERE user_id=?::uuid", Integer.class, owner)).isZero();
    }

    @Test
    void updatingThenDeletingLeavesNoCredentialAndLateWritesCannotRestoreIt() {
        var created = service.create("session", "create", input);
        var updated = service.update("session", created.id(), "update", new ModelConnectionRequest("renamed", input.baseUrl(), input.protocol(), input.preset(), "unverified-replacement", "1"));
        assertThat(updated.configurationVersion()).isEqualTo("2");
        assertThat(service.delete("session", created.id(), "delete", "2").deleted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT credential_ciphertext FROM personal_model_connection WHERE id=?::uuid", String.class, created.id())).isNull();
        assertThatThrownBy(() -> service.update("session", created.id(), "late", input)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_CONNECTION_NOT_FOUND"));
    }

    @Test
    void receiptBodyExpiryRetainsTheCreateIdentityInsteadOfReexecutingIt() {
        service.create("session", "expired", input);
        jdbc.update("UPDATE personal_model_command SET completed_at=? WHERE user_id=?::uuid", Timestamp.from(NOW.minusSeconds(8 * 86400)), owner);
        service.expireReceipts();
        assertThatThrownBy(() -> service.create("session", "expired", input)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_RECEIPT_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_connection WHERE user_id=?::uuid", Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void sharedCredentialModelsAndBothDeletionScopesPersistWithoutPhysicalCascades() {
        var connection = service.create("session", "connection", input);
        String encrypted = sql.getMapper(ModelConnectionRepository.class).find(connection.id()).credentialCiphertext();
        var first = models.create("session", connection.id(), "flash", model("Flash", "deepseek-flash", null));
        var second = models.create("session", connection.id(), "pro", model("Pro", "deepseek-v4-pro", null));
        assertThat(models.configuration("session", first.id())).isEqualTo(first);
        assertThat(sql.getMapper(ModelConnectionRepository.class).find(connection.id()).credentialCiphertext()).isEqualTo(encrypted);
        models.delete("session", first.id(), "delete-model", "1");
        assertThat(models.configurations("session", connection.id(), null).models()).containsExactly(second);
        assertThat(service.connection("session", connection.id()).keyConfigured()).isTrue();
        service.delete("session", connection.id(), "delete-connection", "1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_configuration WHERE connection_id=?::uuid AND deleted_at IS NULL", Integer.class, connection.id())).isZero();
        assertThatThrownBy(() -> models.update("session", second.id(), "late-update", model("Pro", second.modelId(), "1")))
            .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo("MODEL_CONFIGURATION_NOT_FOUND"));
    }

    @Test
    void modelDisplayAndCallConfigurationVersionsHaveDifferentPersistenceSemantics() {
        var connection = service.create("session", "connection", input);
        var first = models.create("session", connection.id(), "create", model("Flash", "deepseek-flash", null));
        var renamed = models.update("session", first.id(), "rename", model("New name", first.modelId(), "1"));
        assertThat(renamed.configurationVersion()).isEqualTo("1");
        var changed = models.update("session", first.id(), "change", model("New name", "deepseek-v4-pro", "2"));
        assertThat(changed.configurationVersion()).isEqualTo("2");
        assertThat(models.update("session", first.id(), "change", model("New name", "deepseek-v4-pro", "2"))).isEqualTo(changed);
    }

    @Test
    void successfulModelCommandsReplayAfterParentProtocolChangesAndDeletionWithoutRevival() {
        var connection = service.create("session", "connection", new ModelConnectionRequest("Anthropic", "https://example.test", ModelProtocol.ANTHROPIC, ModelProviderPreset.CUSTOM, "fixture-provider-key", null));
        var request = new ModelConfigurationRequest("Thinking", "fixture", 100000, 4096, ModelThinkingMode.ON, null, 2048, null, null);
        var created = models.create("session", connection.id(), "create", request);
        var edit = new ModelConfigurationRequest("Renamed", "fixture", 100000, 4096, ModelThinkingMode.ON, null, 2048, null, "1");
        var updated = models.update("session", created.id(), "update", edit);
        service.update("session", connection.id(), "change-protocol", new ModelConnectionRequest("Chat", "https://example.test", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.CUSTOM, null, "1"));
        assertThat(models.create("session", connection.id(), "create", request)).isEqualTo(created);
        assertThat(models.update("session", created.id(), "update", edit)).isEqualTo(updated);
        service.delete("session", connection.id(), "delete", "2");
        assertThat(models.create("session", connection.id(), "create", request)).isEqualTo(created);
        assertThat(models.update("session", created.id(), "update", edit)).isEqualTo(updated);
        assertThatThrownBy(() -> models.create("session", connection.id(), "fresh", request)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_CONNECTION_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_configuration WHERE connection_id=?::uuid AND deleted_at IS NULL", Integer.class, connection.id())).isZero();
    }

    private ModelConfigurationRequest model(String name, String id, String version) {
        return new ModelConfigurationRequest(name, id, 1000000, 4096, ModelThinkingMode.OFF, null, null, null, version);
    }

    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> invoke(barrier, first)); var b = executor.submit(() -> invoke(barrier, second));
            return List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        }
    }
    private Object invoke(CyclicBarrier barrier, Callable<Object> operation) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try { return operation.call(); } catch (BusinessException rejected) { return rejected.code(); }
    }
    private <T> T transactional(T target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
}
