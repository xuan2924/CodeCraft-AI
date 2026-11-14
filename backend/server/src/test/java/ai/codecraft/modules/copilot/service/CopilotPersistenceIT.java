package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.repository.CopilotRetentionRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelCallResultDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import ai.codecraft.modules.modelaccess.service.ModelCheckService;
import ai.codecraft.modules.modelaccess.service.ModelCommandReceipts;
import ai.codecraft.modules.modelaccess.service.ModelConfigurationService;
import ai.codecraft.modules.modelaccess.service.ModelConnectionService;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import ai.codecraft.modules.modelaccess.service.ModelInvocationService;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.repository.ExerciseModelRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.StorageCapacityRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.modules.workspace.service.ExerciseDataService;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import ai.codecraft.modules.workspace.service.ExerciseService;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.ProblemTransactionService;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import ai.codecraft.modules.workspace.service.StorageCapacityStore;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@EnabledIfEnvironmentVariable(named = "COPILOT_IT_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CopilotPersistenceIT {
    private static final String SESSION = "fixture-session";
    private static final String CONTROL = "c".repeat(43);
    private final MutableClock clock = new MutableClock();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final CopilotProperties limits = new CopilotProperties(2, 262144, 1048576, 16, 3, Duration.ofSeconds(130));
    private final ModelCallProperties calls = new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 8, 8388608, 65536, 262144);
    private SqlSessionTemplate sql;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private CopilotTaskStore tasks;
    private CopilotExecutionStore execution;
    private CopilotRunner runner;
    private ModelCallDriver driver;
    private ExerciseService files;
    private StorageMeasurement measurement;
    private ExerciseDataService data;
    private String exercise;
    private Runnable updateSelectedKey;
    private Runnable deleteSelectedModel;
    private Runnable switchSelectedModel;
    private String alternateModelId;

    @BeforeAll
    void migrate() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("COPILOT_IT_JDBC_URL"), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); transactions = new DataSourceTransactionManager(source);
        var config = new Configuration(); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        for (Class<?> mapper : List.of(IdentityRepository.class, ModelConnectionRepository.class, ModelConfigurationRepository.class,
            ExerciseRepository.class, ExerciseModelRepository.class, WorkspaceWriteRepository.class, StorageCapacityRepository.class,
            CopilotTaskRepository.class, CopilotRetentionRepository.class)) config.addMapper(mapper);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void seed() throws Exception {
        clock.now = Instant.parse("2026-01-01T00:00:00Z");
        String owner = UUID.randomUUID().toString(); exercise = UUID.randomUUID().toString();
        String instance = UUID.randomUUID().toString(); String preparation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES(?::uuid,?,'fixture','USER',false)", owner, "u_" + owner.substring(0, 20));
        var identity = mock(IdentityTransactionService.class);
        var grant = new SessionAuthorizationDTO(owner, "USER", false, 1, clock.instant().plusSeconds(3600));
        when(identity.authorizeMutation(SESSION)).thenReturn(grant);
        when(identity.authorization(SESSION)).thenReturn(grant);
        when(identity.authorizePrivateDelivery(SESSION)).thenReturn(grant);
        when(identity.tryAuthorizeMutation(SESSION)).thenReturn(grant);
        var secrets = mock(IdentitySecrets.class);
        when(secrets.fingerprint(anyString())).thenAnswer(call -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(call.<String>getArgument(0).getBytes(StandardCharsets.UTF_8))));
        var connectionRepository = sql.getMapper(ModelConnectionRepository.class);
        var modelRepository = sql.getMapper(ModelConfigurationRepository.class);
        var receipts = new ModelCommandReceipts(connectionRepository, secrets, json, clock);
        var cipher = new ModelCredentialCipher(new ModelSecurityProperties("fixture", "fixture:" + Base64.getEncoder().encodeToString(new byte[32])));
        var connections = proxy(new ModelConnectionService(connectionRepository, identity, cipher, new ModelConnectionValidator(), receipts, clock), ModelConnectionService.class);
        var models = proxy(new ModelConfigurationService(modelRepository, connectionRepository, identity, new ModelConnectionValidator(), receipts, clock), ModelConfigurationService.class);
        var connection = connections.create(SESSION, "connection", new ModelConnectionRequest("Fixture", "https://example.test", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.CUSTOM, "fixture-not-real", null));
        var model = models.create(SESSION, connection.id(), "model", new ModelConfigurationRequest("Fixture", "fixture", 1000000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        updateSelectedKey = () -> connections.update(SESSION, connection.id(), "update-key", new ModelConnectionRequest("Fixture", "https://example.test", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.CUSTOM, "fixture-replacement", connection.version()));
        deleteSelectedModel = () -> models.delete(SESSION, model.id(), "delete-model", model.version());
        var exercises = sql.getMapper(ExerciseRepository.class);
        exercises.insert(new ExerciseEntity(exercise, owner, UUID.randomUUID().toString(), instance, preparation, "PREPARING", null, "[]", 1, clock.instant(), null, null, null, null, null));
        exercises.prepared(exercise, instance, preparation, 1, "[\"src/Main.java\"]");
        exercises.start(exercise, clock.instant(), clock.instant().plusSeconds(1800));
        jdbc.update("INSERT INTO workspace_control(exercise_id,gate_epoch,gate_state,credential_hash) VALUES(?::uuid,1,'OPEN',?)", exercise, IdentitySecrets.digest(CONTROL));
        var writes = sql.getMapper(WorkspaceWriteRepository.class);
        var coordination = proxy(new WorkspaceCoordinationStore(writes, exercises, json), WorkspaceCoordinationStore.class);
        var invocations = proxy(new ModelInvocationService(identity, connectionRepository, modelRepository, cipher), ModelInvocationService.class);
        var selection = proxy(new ExerciseModelService(sql.getMapper(ExerciseModelRepository.class), exercises, identity, coordination, invocations,
            mock(ModelCheckService.class), clock, secrets), ExerciseModelService.class);
        selection.select(SESSION, exercise, CONTROL, "select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
        var alternate = models.create(SESSION, connection.id(), "alternate", new ModelConfigurationRequest("Alternate", "alternate", 1000000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        alternateModelId = alternate.id();
        switchSelectedModel = () -> selection.select(SESSION, exercise, CONTROL, "switch", new ModelInvocationReferenceDTO(connection.id(), alternate.id(), 1, 1));
        data = new ExerciseDataService(List.of(new CopilotExerciseData(sql.getMapper(CopilotRetentionRepository.class))));
        var budget = new StorageProperties(536870912, 268435456, 20);
        var capacityRepository = sql.getMapper(StorageCapacityRepository.class);
        capacityRepository.initialize(exercise);
        var capacityStore = proxy(new StorageCapacityStore(capacityRepository, exercises, writes, identity, budget, clock, data), StorageCapacityStore.class);
        var lifecycle = proxy(new ExerciseTransactionService(exercises, identity, mock(ProblemTransactionService.class), clock, secrets, coordination,
            new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), capacityStore, selection), ExerciseTransactionService.class);
        var operations = proxy(new WorkspaceAiOperationService(identity, exercises, writes, capacityStore, clock), WorkspaceAiOperationService.class);
        var repository = sql.getMapper(CopilotTaskRepository.class);
        tasks = proxy(new CopilotTaskStore(repository, identity, secrets, lifecycle, coordination, selection, operations, clock), CopilotTaskStore.class);
        execution = proxy(new CopilotExecutionStore(repository, identity, coordination, selection, operations, limits, clock, new CopilotRequestFactory()), CopilotExecutionStore.class);
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 1, 0);
        var capacity = mock(StorageCapacityService.class);
        when(capacity.admit(anyString(), anyString(), any())).thenAnswer(call -> call.<Function<StorageMeasurement, Object>>getArgument(2).apply(measurement));
        files = mock(ExerciseService.class);
        when(files.find(SESSION, exercise)).thenReturn(new ExerciseResponse(exercise, "problem", instance, preparation, "ACTIVE", "1", "1", List.of("src/Main.java"), clock.instant(), clock.instant().plusSeconds(1800), "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null));
        when(files.read(SESSION, exercise, "src/Main.java")).thenReturn(new WorkspaceFileResponse(instance, "1", "src/Main.java", "class Main {}"));
        driver = mock(ModelCallDriver.class);
        // Keep non-streaming scenarios focused on completed responses; streaming cases replace this bridge explicitly.
        when(driver.call(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> driver.call(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
        runner = new CopilotRunner(execution, capacity, driver, new CopilotReadTools(files, json), limits, calls, secrets, json);
    }

    @Test
    void serialToolResultsArePairedAndDurableAndDuplicateAdmissionDoesNotRepeatPaidCalls() throws Exception {
        var view = ToolExecutionRequest.builder().id("read-1").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        var glob = ToolExecutionRequest.builder().id("glob-1").name("glob_files").arguments("{\"pattern\":\"**/*.java\"}").build();
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            if (count.getAndIncrement() == 0) return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(view, glob))).build(), 100);
            assertThat(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast).map(ToolExecutionResultMessage::id)).containsExactly("read-1", "glob-1");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("The saved Main class is empty.")).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect the saved Main class", true, measurement);
        assertThat(data.quiescent(exercise)).isFalse();
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect the saved Main class", true, measurement).id()).isEqualTo(task.id());
        runner.run(task.id(), UUID.randomUUID().toString());
        verify(driver, times(2)).call(any(), any(), any(), anyInt(), any());
        assertThat(data.retainedBytes(exercise)).isPositive();
        assertThat(data.quiescent(exercise)).isTrue();
        assertThat(tasks.messages(SESSION, exercise, -1).stream().map(value -> value.kind())).containsExactly("USER", "ASSISTANT", "TOOL", "TOOL", "ASSISTANT");
    }

    @Test
    void explicitSwitchTakesEffectOnlyOnTheNextModelRequestAfterTheOldBatch() throws Exception {
        var view = ToolExecutionRequest.builder().id("read-before-switch").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            if (count.getAndIncrement() == 0) {
                switchSelectedModel.run();
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(view))).build(), 100);
            }
            ModelInvocationDTO target = call.getArgument(0);
            assertThat(target.model().id()).isEqualTo(alternateModelId);
            ChatRequest request = call.getArgument(1);
            assertThat(request.messages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance)).isFalse();
            assertThat(request.messages().toString()).contains("Read-only recorded tool evidence");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Reply from the selected model.")).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.delivery(SESSION, task.id()).textHistory()).isTrue();
        verify(driver, times(2)).call(any(), any(), any(), anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inFlightRevisionAndBatchSurviveAnEditOrDeletionButNoNewCallUsesTheOldKey(boolean deleting) throws Exception {
        var view = ToolExecutionRequest.builder().id("read-1").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            if (deleting) deleteSelectedModel.run(); else updateSelectedKey.run();
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(view))).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("PAUSED");
        assertThat(tasks.find(SESSION, task.id()).reason()).isEqualTo(deleting ? "MODEL_CONFIGURATION_NOT_FOUND" : "MODEL_VERSION_CONFLICT");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
        assertThat(tasks.messages(SESSION, exercise, -1).stream().map(value -> value.kind())).containsExactly("USER", "ASSISTANT", "TOOL");
    }

    @Test
    void stoppingDuringOneReadKeepsItsResultPairsTheRemainingCallsAndDoesNotStartAnotherRequest() throws Exception {
        var first = ToolExecutionRequest.builder().id("first").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        var second = ToolExecutionRequest.builder().id("second").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(first, second))).build(), 100));
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        assertThatThrownBy(() -> tasks.accept(SESSION, exercise, CONTROL, "another", "Do not queue", true, measurement)).hasMessage("COPILOT_BUSY");
        String instance = files.find(SESSION, exercise).workspaceInstanceId();
        when(files.read(SESSION, exercise, "src/Main.java")).thenAnswer(call -> {
            assertThat(sql.getMapper(StorageCapacityRepository.class).reservedBytes(exercise)).isGreaterThanOrEqualTo(2 * 1024 * 1024);
            tasks.stop(SESSION, task.id(), "stop");
            return new WorkspaceFileResponse(instance, "1", "src/Main.java", "class Main {}");
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        var results = tasks.messages(SESSION, exercise, -1).stream().filter(value -> value.kind().equals("TOOL")).toList();
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().payload()).contains("class Main");
        assertThat(results.getLast().payload()).contains("NOT_EXECUTED");
        verify(files, times(1)).read(SESSION, exercise, "src/Main.java");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
        assertThat(data.quiescent(exercise)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialResponsesStaySeparateFromConfirmedMessagesAndSurviveInterruptedCalls(boolean completes) throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        doAnswer(call -> {
            Consumer<String> partial = call.getArgument(5);
            partial.accept("Unconfirmed reply, not tool arguments");
            assertThat(tasks.delivery(SESSION, task.id()).partial().text()).contains("Unconfirmed reply");
            assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("RUNNING");
            assertThat(tasks.messages(SESSION, exercise, -1)).hasSize(1);
            verify(files, never()).read(any(), any(), any());
            if (!completes) throw new BusinessException(ModelAccessErrorCode.MODEL_NETWORK_UNAVAILABLE);
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Confirmed reply")).tokenUsage(new TokenUsage(100, 20, 120)).build(), 100);
        }).when(driver).call(any(), any(), any(), anyInt(), any(), any());
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo(completes ? "SUCCEEDED" : "FAILED");
        assertThat(tasks.delivery(SESSION, task.id()).partial().text()).isEqualTo(completes ? "" : "Unconfirmed reply, not tool arguments");
        assertThat(tasks.messages(SESSION, exercise, -1)).hasSize(completes ? 2 : 1);
        String usage = jdbc.queryForObject("SELECT usage_payload FROM copilot_turn WHERE task_id=?::uuid", String.class, task.id());
        if (completes) assertThat(json.readTree(usage).get("inputTokenCount").asInt()).isEqualTo(100);
        else assertThat(usage).isNull();
        String configuration = jdbc.queryForObject("SELECT configuration_payload FROM copilot_turn WHERE task_id=?::uuid", String.class, task.id());
        assertThat(configuration).contains("OPENAI_CHAT", "https://example.test").doesNotContain("fixture-not-real", "apiKey");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void repeatedIdenticalToolsWithoutProgressStopWithoutAFixedNormalRoundLimit() throws Exception {
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            var view = ToolExecutionRequest.builder().id("read-" + count.incrementAndGet()).name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(view))).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("FAILED");
        assertThat(tasks.find(SESSION, task.id()).reason()).isEqualTo("NO_PROGRESS");
        assertThat(count.get()).isEqualTo(4);
        assertThat(data.quiescent(exercise)).isTrue();
    }

    @Test
    void stoppingBeforeDispatchNeverCallsTheProvider() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        assertThat(tasks.stop(SESSION, task.id(), "stop").state()).isEqualTo("STOPPED");
        runner.run(task.id(), UUID.randomUUID().toString());
        verify(driver, never()).call(any(), any(), any(), anyInt(), any());
        assertThat(data.quiescent(exercise)).isTrue();
    }

    @Test
    void expiredDispatchedRequestIsInterruptedWithoutAnotherPaidCall() {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        String worker = UUID.randomUUID().toString();
        assertThat(execution.claim(task.id(), worker)).isNotNull();
        assertThat(execution.beginModel(task.id(), worker, measurement)).isNotNull();
        clock.now = clock.now.plusSeconds(130);
        execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(data.quiescent(exercise)).isTrue();
    }

    @Test
    void deletionWaitsForTaskQuiescenceAndErasesEvidenceWithoutDeletingPersonalModels() {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(data.retainedBytes(exercise)).isPositive();
        tasks.stop(SESSION, task.id(), "stop");
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(data.retainedBytes(exercise)).isZero();
        assertThat(sql.getMapper(ModelConfigurationRepository.class).find(alternateModelId)).isNotNull();
    }

    @Test
    void capacityFailureCannotCreateATaskOrConsumeProtectedRecoverySpace() {
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 1, 268435456);
        assertThatThrownBy(() -> tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement)).isInstanceOf(BusinessException.class);
        assertThat(tasks.latest(SESSION, exercise)).isNull();
    }

    private <T> T proxy(T value, Class<T> type) {
        var proxy = new ProxyFactory(value);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
