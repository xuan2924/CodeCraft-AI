package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.GateResponse;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import io.grpc.Status;
import ai.codecraft.modules.copilot.repository.CopilotRetentionRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.identity.service.IdentityRevocationNotifier;
import ai.codecraft.modules.identity.service.IdentityRevocationBarrier;
import ai.codecraft.modules.identity.repository.RevocationIntentRepository;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.gateway.ModelSdkClientFactory;
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
import ai.codecraft.modules.workspace.service.WorkspaceBatchCodec;
import ai.codecraft.modules.workspace.service.WorkspaceWriteTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceWriteCoordinator;
import ai.codecraft.modules.workspace.service.WorkspaceMaintenanceCoordinator;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
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
    private ModelConnectionService connections;
    private ModelConfigurationService models;
    private ExerciseModelService selection;
    private IdentityTransactionService identity;
    private CopilotProposalStore proposals;
    private SandboxWorkspaceGateway sandbox;
    private WorkspaceWriteCoordinator writeCoordinator;
    private StorageCapacityService capacity;
    private CopilotToolExecutor executor;

    @BeforeAll
    void migrate() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("COPILOT_IT_JDBC_URL"), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); transactions = new DataSourceTransactionManager(source);
        var config = new Configuration(); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        for (Class<?> mapper : List.of(IdentityRepository.class, ModelConnectionRepository.class, ModelConfigurationRepository.class,
            ExerciseRepository.class, ExerciseModelRepository.class, WorkspaceWriteRepository.class, StorageCapacityRepository.class,
            CopilotTaskRepository.class, CopilotRetentionRepository.class, CopilotProposalRepository.class)) config.addMapper(mapper);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void seed() throws Exception {
        clock.now = "1".equals(System.getenv("COPILOT_LIVE_DEEPSEEK")) ? Instant.now() : Instant.parse("2026-01-01T00:00:00Z");
        String owner = UUID.randomUUID().toString(); exercise = UUID.randomUUID().toString();
        String instance = UUID.randomUUID().toString(); String preparation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES(?::uuid,?,'fixture','USER',false)", owner, "u_" + owner.substring(0, 20));
        identity = mock(IdentityTransactionService.class);
        var locks = proxy(new IdentityTransactionService(sql.getMapper(IdentityRepository.class), clock, mock(IdentityRevocationNotifier.class),
            mock(RevocationIntentRepository.class), mock(IdentityRevocationBarrier.class)), IdentityTransactionService.class);
        doAnswer(call -> { locks.lockResourceOwner(call.getArgument(0)); return null; }).when(identity).lockResourceOwner(anyString());
        var grant = new SessionAuthorizationDTO(owner, "USER", false, 1, clock.instant().plusSeconds(3600));
        when(identity.authorizeMutation(SESSION)).thenAnswer(call -> { locks.lockResourceOwner(owner); return grant; });
        when(identity.authorization(SESSION)).thenReturn(grant);
        when(identity.authorizePrivateDelivery(SESSION)).thenAnswer(call -> { locks.lockResourceOwner(owner); return grant; });
        when(identity.tryAuthorizeMutation(SESSION)).thenAnswer(call -> { locks.lockResourceOwner(owner); return grant; });
        var secrets = mock(IdentitySecrets.class);
        when(secrets.fingerprint(anyString())).thenAnswer(call -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(call.<String>getArgument(0).getBytes(StandardCharsets.UTF_8))));
        var connectionRepository = sql.getMapper(ModelConnectionRepository.class);
        var modelRepository = sql.getMapper(ModelConfigurationRepository.class);
        var receipts = new ModelCommandReceipts(connectionRepository, secrets, json, clock);
        byte[] fixtureKey = new byte[32];
        if ("1".equals(System.getenv("COPILOT_LIVE_DEEPSEEK"))) new SecureRandom().nextBytes(fixtureKey);
        var cipher = new ModelCredentialCipher(new ModelSecurityProperties("fixture", "fixture:" + Base64.getEncoder().encodeToString(fixtureKey)));
        connections = proxy(new ModelConnectionService(connectionRepository, identity, cipher, new ModelConnectionValidator(), receipts, clock), ModelConnectionService.class);
        models = proxy(new ModelConfigurationService(modelRepository, connectionRepository, identity, new ModelConnectionValidator(), receipts, clock), ModelConfigurationService.class);
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
        selection = proxy(new ExerciseModelService(sql.getMapper(ExerciseModelRepository.class), exercises, identity, coordination, invocations,
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
        tasks = proxy(new CopilotTaskStore(repository, identity, secrets, lifecycle, coordination, selection, operations, clock, sql.getMapper(CopilotProposalRepository.class)), CopilotTaskStore.class);
        execution = proxy(new CopilotExecutionStore(repository, identity, coordination, selection, operations, limits, clock, new CopilotRequestFactory(), sql.getMapper(CopilotProposalRepository.class), secrets), CopilotExecutionStore.class);
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 1, 0);
        sandbox = mock(SandboxWorkspaceGateway.class);
        when(sandbox.measureStorage(any())).thenAnswer(call -> measurement);
        capacity = new StorageCapacityService(capacityStore, sandbox, mock(WorkspaceMaintenanceCoordinator.class));
        files = mock(ExerciseService.class);
        when(files.find(SESSION, exercise)).thenReturn(new ExerciseResponse(exercise, "problem", instance, preparation, "ACTIVE", "1", "1", List.of("src/Main.java"), clock.instant(), clock.instant().plusSeconds(1800), "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null));
        when(files.read(SESSION, exercise, "src/Main.java")).thenReturn(new WorkspaceFileResponse(instance, "1", "src/Main.java", "class Main {}"));
        driver = mock(ModelCallDriver.class);
        // Keep non-streaming scenarios focused on completed responses; streaming cases replace this bridge explicitly.
        when(driver.call(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> driver.call(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
        var codec = new WorkspaceBatchCodec(json);
        var writeStore = proxy(new WorkspaceWriteTransactionService(identity, secrets, exercises, writes, clock, capacityStore), WorkspaceWriteTransactionService.class);
        writeCoordinator = new WorkspaceWriteCoordinator(coordination, sandbox, codec);
        proposals = proxy(new CopilotProposalStore(sql.getMapper(CopilotProposalRepository.class), repository, tasks, execution, identity, secrets,
            lifecycle, coordination, writeStore, operations, codec, json, clock), CopilotProposalStore.class);
        executor = new CopilotToolExecutor(new CopilotReadTools(files, json), new CopilotProposalPreparation(files, codec, json), proposals, json);
        runner = new CopilotRunner(execution, capacity, driver, executor, limits, calls);
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
    void foreignOwnersStaleControlAndExpiredExercisesCannotAdmitOrReadWork() throws Exception {
        assertThatThrownBy(() -> tasks.accept(SESSION, exercise, "x".repeat(43), "stale", "Inspect", true, measurement)).hasMessage("STALE_CONTROL");
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        var other = new SessionAuthorizationDTO(UUID.randomUUID().toString(), "USER", false, 1, clock.instant().plusSeconds(3600));
        when(identity.authorizeMutation("other")).thenReturn(other);
        when(identity.authorizePrivateDelivery("other")).thenReturn(other);
        when(identity.authorization("other")).thenReturn(other);
        assertThatThrownBy(() -> tasks.find("other", task.id())).hasMessage("COPILOT_NOT_FOUND");
        assertThatThrownBy(() -> tasks.stop("other", task.id(), "stop")).hasMessage("COPILOT_NOT_FOUND");
        assertThatThrownBy(() -> tasks.messages("other", exercise, -1)).hasMessage("RESOURCE_NOT_FOUND");
        tasks.stop(SESSION, task.id(), "stop");
        clock.now = clock.instant().plusSeconds(3601);
        assertThatThrownBy(() -> tasks.accept(SESSION, exercise, CONTROL, "expired", "Inspect", true, measurement)).hasMessage("INVALID_STATE");
        verify(driver, never()).call(any(), any(), any(), anyInt(), any(), any());
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
    @EnabledIfEnvironmentVariable(named = "COPILOT_LIVE_DEEPSEEK", matches = "1")
    void realDeepSeekReadsSavedFixtureCodeAndRepliesWithoutAnImplicitRetryLoop() throws Exception {
        String key = System.getenv("DeepSeek");
        assertThat(key != null && !key.isBlank()).as("DeepSeek credential must be supplied through the environment").isTrue();
        var connection = connections.create(SESSION, "live-connection", new ModelConnectionRequest("Live fixture", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, key, null));
        var model = models.create(SESSION, connection.id(), "live-model", new ModelConfigurationRequest("Live fixture", "deepseek-flash", 100000, 1024, ModelThinkingMode.OFF, null, null, null, null));
        selection.select(SESSION, exercise, CONTROL, "live-select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
        var count = new AtomicInteger();
        try (var live = new ModelCallDriver(new ModelSdkClientFactory(new ModelConnectionValidator(), calls), calls, clock)) {
            doAnswer(call -> {
                // Test-only paid-call budget; production tasks have no fixed round limit.
                if (count.incrementAndGet() > 4) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
                return live.call(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
            }).when(driver).call(any(), any(), any(), anyInt(), any(), any());
            var task = tasks.accept(SESSION, exercise, CONTROL, "live-start", "Use view_file once to read src/Main.java. Then reply briefly with the class name from the saved file. Do not use other tools.", true, measurement);
            runner.run(task.id(), UUID.randomUUID().toString());
            assertThat(tasks.find(SESSION, task.id()).state()).as("live task reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("SUCCEEDED");
            verify(files, times(1)).read(SESSION, exercise, "src/Main.java");
            var messages = tasks.messages(SESSION, exercise, -1);
            assertThat(messages.getLast().payload()).contains("Main");
            assertThat(messages.stream().filter(value -> value.kind().equals("TOOL")).count()).isEqualTo(1);
            assertThat(count.get()).isBetween(2, 4);
            var samples = jdbc.queryForList("SELECT input_tokens,output_tokens,total_tokens,usage_payload FROM copilot_turn WHERE task_id=?::uuid ORDER BY ordinal", task.id());
            assertThat(samples).hasSize(count.get());
            for (var sample : samples) assertThat(sample.get("usage_payload")).isNotNull();
            assertThat(jdbc.queryForList("SELECT configuration_payload FROM copilot_turn WHERE task_id=?::uuid", String.class, task.id()).stream().anyMatch(value -> value.contains(key))).isFalse();
            System.out.println("DeepSeek live fixture usage (no request bodies or credentials): " + samples);
        } finally {
            connections.delete(SESSION, connection.id(), "live-cleanup", connection.version());
        }
    }

    @Test
    void capacityFailureCannotCreateATaskOrConsumeProtectedRecoverySpace() {
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 1, 268435456);
        assertThatThrownBy(() -> tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement)).isInstanceOf(BusinessException.class);
        assertThat(tasks.latest(SESSION, exercise)).isNull();
    }

    @Test
    void approvedProposalResumesTheOriginalBatchWithoutRegeneratingOrReapplyingIt() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        assertThat(proposals.detail(SESSION, proposal).files().getFirst().before()).isEqualTo("class Main {}");
        assertThat(tasks.messages(SESSION, exercise, -1)).hasSize(3);
        runner = new CopilotRunner(execution, capacity, driver, executor, limits, calls);
        var approve = new CopilotProposalDecisionRequest("APPROVE", null);
        assertThat(proposals.decide(SESSION, proposal, CONTROL, "approve", approve, measurement).state()).isEqualTo("APPLYING");
        assertThat(proposals.decide(SESSION, proposal, "obsolete", "approve", approve, measurement).state()).isEqualTo("APPLYING");
        writeCoordinator.recover(); proposals.recoverTask(task.id());
        assertThat(proposals.detail(SESSION, proposal).proposal().state()).isEqualTo("APPLIED");
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(workspace.applies.get()).isEqualTo(1);
        assertThat(tasks.messages(SESSION, exercise, -1).stream().map(value -> value.kind())).containsExactly("USER", "ASSISTANT", "TOOL", "TOOL", "TOOL", "ASSISTANT");
        verify(files, times(3)).read(SESSION, exercise, ProposalWorkspace.PATH);
        assertThat(proposals.decide(SESSION, proposal, "obsolete", "approve", approve, measurement).resultRevision()).isEqualTo("2");
        verify(driver, times(2)).call(any(), any(), any(), anyInt(), any());
        assertThat(data.quiescent(exercise)).isTrue();
        assertThat(data.retainedBytes(exercise)).isPositive();
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(sql.getMapper(CopilotProposalRepository.class).find(proposal)).isNull();
        assertThat(data.retainedBytes(exercise)).isZero();
        assertThat(sql.getMapper(ModelConfigurationRepository.class).find(alternateModelId)).isNotNull();
    }

    @Test
    void rejectionContinuesTheTaskAndDoesNotRegisterTheSameRejectedPatchAgain() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        proposals.decide(SESSION, proposal, CONTROL, "reject", new CopilotProposalDecisionRequest("REJECT", "Keep the original class"), null);
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(workspace.proposal()))).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Keeping the original code.")).build(), 100));
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(proposals.list(SESSION, task.id(), 0).items()).hasSize(1);
        var messages = tasks.messages(SESSION, exercise, -1);
        assertThat(messages.stream().filter(value -> value.kind().equals("TOOL")).map(value -> value.payload()).toList())
            .anyMatch(value -> value.contains("Keep the original class")).anyMatch(value -> value.contains("PROPOSAL_PREVIOUSLY_REJECTED"));
        assertThat(workspace.applies.get()).isZero();
        verify(driver, times(3)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void stopBeforeTheApprovedWriteDispatchFencesItInsteadOfApplyingIt() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        proposals.decide(SESSION, proposal, CONTROL, "approve", new CopilotProposalDecisionRequest("APPROVE", null), measurement);
        assertThat(tasks.stop(SESSION, task.id(), "stop").state()).isEqualTo("STOPPING");
        writeCoordinator.recover(); proposals.recoverTask(task.id()); execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(workspace.applies.get()).isZero();
        assertThat(proposals.detail(SESSION, proposal).proposal().state()).isEqualTo("FAILED");
        assertThat(data.quiescent(exercise)).isTrue();
    }

    @Test
    void anAppliedWriteWithLostResponsesRemainsUnknownUntilItsReceiptIsRecoveredAndIsNeverReplayed() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        proposals.decide(SESSION, proposal, CONTROL, "approve", new CopilotProposalDecisionRequest("APPROVE", null), measurement);
        workspace.loseApplyResponse = true; workspace.unavailableReceipts = true;
        writeCoordinator.recover();
        assertThat(tasks.stop(SESSION, task.id(), "stop").state()).isEqualTo("STOPPING");
        proposals.recoverTask(task.id()); execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPING");
        assertThat(data.quiescent(exercise)).isFalse();
        workspace.unavailableReceipts = false;
        writeCoordinator.recover(); proposals.recoverTask(task.id()); execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(proposals.detail(SESSION, proposal).proposal().state()).isEqualTo("APPLIED");
        assertThat(workspace.applies.get()).isEqualTo(1);
        assertThat(data.quiescent(exercise)).isTrue();
    }

    @Test
    void approvalRechecksOwnershipControlAndBaselineAndCannotAuthorizeNewContentWithAnOldKey() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        var approve = new CopilotProposalDecisionRequest("APPROVE", null);
        assertThatThrownBy(() -> proposals.decide(SESSION, proposal, "stale", "approve", approve, measurement)).isInstanceOf(BusinessException.class);
        var foreign = new SessionAuthorizationDTO(UUID.randomUUID().toString(), "USER", false, 1, clock.instant().plusSeconds(3600));
        when(identity.authorizeMutation("foreign")).thenReturn(foreign);
        when(identity.authorizePrivateDelivery("foreign")).thenReturn(foreign);
        assertThatThrownBy(() -> proposals.detail("foreign", proposal)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> proposals.decide("foreign", proposal, CONTROL, "approve", approve, measurement)).isInstanceOf(BusinessException.class);
        assertThat(proposals.detail(SESSION, proposal).proposal().state()).isEqualTo("PENDING");
        sql.getMapper(WorkspaceWriteRepository.class).observed(exercise, 2, "[\"" + ProposalWorkspace.PATH + "\"]");
        assertThat(proposals.decide(SESSION, proposal, CONTROL, "approve", approve, measurement).state()).isEqualTo("INVALIDATED");
        assertThatThrownBy(() -> proposals.decide(SESSION, proposal, CONTROL, "approve", new CopilotProposalDecisionRequest("REJECT", "changed"), null))
            .isInstanceOf(BusinessException.class);
        assertThat(workspace.applies.get()).isZero();
    }

    @Test
    void capacityFailureDoesNotConsumeConsentAndExpiryCancelsIt() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        var approve = new CopilotProposalDecisionRequest("APPROVE", null);
        var full = new StorageMeasurement(exercise, "A".repeat(26), 2, 268435456);
        assertThatThrownBy(() -> proposals.decide(SESSION, proposal, CONTROL, "approve", approve, full)).isInstanceOf(BusinessException.class);
        assertThat(proposals.detail(SESSION, proposal).proposal().state()).isEqualTo("PENDING");
        clock.now = clock.instant().plusSeconds(1801);
        assertThat(proposals.decide(SESSION, proposal, CONTROL, "approve", approve, measurement).state()).isEqualTo("CANCELLED");
        execution.recover(task.id());
        assertThat(workspace.applies.get()).isZero();
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(tasks.find(SESSION, task.id()).reason()).isEqualTo("EXERCISE_ENDED");
    }

    @Test
    void explicitDecisionCanReauthorizeContinuationButObservingAPendingProposalDoesNot() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        String owner = sql.getMapper(CopilotTaskRepository.class).find(task.id()).userId();
        var fresh = new SessionAuthorizationDTO(owner, "USER", false, 1, clock.instant().plusSeconds(3600));
        when(identity.authorizeMutation("renewed")).thenAnswer(call -> { identity.lockResourceOwner(owner); return fresh; });
        when(identity.authorizePrivateDelivery("renewed")).thenAnswer(call -> { identity.lockResourceOwner(owner); return fresh; });
        when(identity.tryAuthorizeMutation("renewed")).thenAnswer(call -> { identity.lockResourceOwner(owner); return fresh; });
        when(identity.authorization("renewed")).thenReturn(fresh);
        when(identity.authorizeMutation(SESSION)).thenThrow(new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED));
        assertThat(proposals.summary("renewed", proposal).state()).isEqualTo("PENDING");
        assertThat(sql.getMapper(CopilotTaskRepository.class).find(task.id()).sessionHash()).isEqualTo(SESSION);
        proposals.decide("renewed", proposal, CONTROL, "reject", new CopilotProposalDecisionRequest("REJECT", "Keep unchanged"), null);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find("renewed", task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(workspace.applies.get()).isZero();
    }

    @Test
    void stopBeforeApprovalCancelsTheProposalAndPreservesCompletedReads() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        tasks.stop(SESSION, task.id(), "stop");
        assertThat(proposals.decide(SESSION, proposal, CONTROL, "late-approve", new CopilotProposalDecisionRequest("APPROVE", null), measurement).state()).isEqualTo("CANCELLED");
        execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(workspace.applies.get()).isZero();
        verify(files, times(2)).read(SESSION, exercise, ProposalWorkspace.PATH);
        assertThat(tasks.messages(SESSION, exercise, -1)).hasSize(5);
    }

    @Test
    void deletingTheSelectedModelDoesNotDiscardApprovedWorkOrRepeatTheOriginalModelCall() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        deleteSelectedModel.run();
        proposals.decide(SESSION, proposal, CONTROL, "approve", new CopilotProposalDecisionRequest("APPROVE", null), measurement);
        writeCoordinator.recover(); proposals.recoverTask(task.id());
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(proposals.detail(SESSION, proposal).proposal().state()).isEqualTo("APPLIED");
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("PAUSED");
        assertThat(workspace.applies.get()).isEqualTo(1);
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "COPILOT_LIVE_DEEPSEEK", matches = "1")
    void realDeepSeekProposesThenContinuesOnlyAfterApprovalAndAConfirmedWrite() throws Exception {
        var workspace = new ProposalWorkspace();
        String key = System.getenv("DeepSeek");
        assertThat(key != null && !key.isBlank()).as("DeepSeek credential must be supplied through the environment").isTrue();
        var connection = connections.create(SESSION, "live-connection", new ModelConnectionRequest("Live proposal fixture", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, key, null));
        var count = new AtomicInteger();
        try (var live = new ModelCallDriver(new ModelSdkClientFactory(new ModelConnectionValidator(), calls), calls, clock)) {
            var model = models.create(SESSION, connection.id(), "live-model", new ModelConfigurationRequest("Live proposal fixture", "deepseek-flash", 100000, 2048, ModelThinkingMode.OFF, null, null, null, null));
            selection.select(SESSION, exercise, CONTROL, "live-select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
            doAnswer(call -> {
                if (count.incrementAndGet() > 8) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
                return live.call(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
            }).when(driver).call(any(), any(), any(), anyInt(), any(), any());
            var task = tasks.accept(SESSION, exercise, CONTROL, "live-start", "Read src/main/java/Main.java. Propose exactly one UPDATE replacing its entire content with: class Main { int answer = 42; }. Wait for approval. After its confirmed application, use view_file to read the saved file, then reply briefly with the value of answer. Do not propose another edit.", true, measurement);
            runner.run(task.id(), UUID.randomUUID().toString());
            assertThat(tasks.find(SESSION, task.id()).state()).as("live proposal reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("WAITING_APPROVAL");
            assertThat(workspace.applies.get()).isZero();
            String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
            assertThat(proposals.detail(SESSION, proposal).files().getFirst().after()).contains("42");
            proposals.decide(SESSION, proposal, CONTROL, "live-approve", new CopilotProposalDecisionRequest("APPROVE", null), measurement);
            writeCoordinator.recover(); proposals.recoverTask(task.id());
            runner.run(task.id(), UUID.randomUUID().toString());
            assertThat(tasks.find(SESSION, task.id()).state()).as("live continuation reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("SUCCEEDED");
            assertThat(workspace.applies.get()).isEqualTo(1);
            assertThat(tasks.messages(SESSION, exercise, -1).getLast().payload()).contains("42");
            assertThat(proposals.detail(SESSION, proposal).proposal().resultRevision()).isEqualTo("2");
            var samples = jdbc.queryForList("SELECT input_tokens,output_tokens,total_tokens,usage_payload FROM copilot_turn WHERE task_id=?::uuid ORDER BY ordinal", task.id());
            for (var sample : samples) assertThat(sample.get("usage_payload")).isNotNull();
            System.out.println("DeepSeek live proposal usage (Go is a deterministic fixture; no credentials or request bodies): " + samples);
        } finally { connections.delete(SESSION, connection.id(), "live-cleanup", connection.version()); }
    }

    private CopilotTaskResponse beginProposal(ProposalWorkspace workspace, boolean trailingRead) throws Exception {
        var proposal = workspace.proposal();
        var read = ToolExecutionRequest.builder().id("after-proposal").name("view_file").arguments("{\"path\":\"" + ProposalWorkspace.PATH + "\"}").build();
        var prefix = ToolExecutionRequest.builder().id("before-proposal").name("view_file").arguments(read.arguments()).build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(trailingRead ? List.of(prefix, proposal, read) : List.of(prefix, proposal))).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Finished.")).build(), 100));
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Propose an edit", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_APPROVAL");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
        return task;
    }

    private final class ProposalWorkspace {
        static final String PATH = "src/main/java/Main.java";
        final String instance = sql.getMapper(ExerciseRepository.class).find(exercise).workspaceInstanceId();
        final AtomicInteger applies = new AtomicInteger();
        final Map<String, MutationReceipt> receipts = new HashMap<>();
        volatile boolean loseApplyResponse;
        volatile boolean unavailableReceipts;
        private String source = "class Main {}";
        private long revision = 1;
        private long epoch = 1;
        private String gateState = "OPEN";
        ProposalWorkspace() {
            sql.getMapper(WorkspaceWriteRepository.class).observed(exercise, 1, "[\"" + PATH + "\"]");
            when(files.find(SESSION, exercise)).thenAnswer(call -> {
                var value = sql.getMapper(ExerciseRepository.class).find(exercise);
                return new ExerciseResponse(exercise, value.problemVersionId(), instance, value.preparationOperationId(), value.state(), value.revision().toString(), "1",
                    List.of(PATH), value.startedAt(), value.deadlineAt(), "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null);
            });
            when(files.read(SESSION, exercise, PATH)).thenAnswer(call -> file());
            when(sandbox.apply(any())).thenAnswer(call -> apply(call.getArgument(0)));
            when(sandbox.query(instance)).thenAnswer(call -> snapshot());
            when(sandbox.mutation(eq(instance), anyString())).thenAnswer(call -> receipt(call.getArgument(1)));
            when(sandbox.gate(instance)).thenAnswer(call -> gate());
            when(sandbox.closeGate(eq(instance), anyString(), anyLong())).thenAnswer(call -> close(call.getArgument(1), call.getArgument(2)));
            when(sandbox.openGate(eq(instance), anyString(), anyLong())).thenAnswer(call -> open(call.getArgument(1), call.getArgument(2)));
        }
        ToolExecutionRequest proposal() throws Exception {
            String arguments = json.writeValueAsString(Map.of("expected_revision", "1", "changes", List.of(new FileChangeRequest(PATH, "UPDATE", "class Main { int answer = 42; }"))));
            return ToolExecutionRequest.builder().id("proposal-call").name("propose_changes").arguments(arguments).build();
        }
        synchronized WorkspaceFileResponse file() { return new WorkspaceFileResponse(instance, Long.toString(revision), PATH, source); }
        synchronized WorkspaceSnapshot snapshot() { return WorkspaceSnapshot.newBuilder().setWorkspaceInstanceId(instance).setPrepared(true).setRevision(revision).addPaths(PATH).build(); }
        synchronized GateResponse gate() { return GateResponse.newBuilder().setWorkspaceInstanceId(instance).setEpoch(epoch).setState(gateState).build(); }
        synchronized GateResponse close(String id, long expected) {
            if (epoch == expected) { epoch++; gateState = "CLOSED"; }
            return GateResponse.newBuilder().setWorkspaceInstanceId(instance).setTransitionId(id).setEpoch(expected + 1).setState("CLOSED").build();
        }
        synchronized GateResponse open(String id, long expected) {
            if (epoch == expected) { epoch++; gateState = "OPEN"; }
            return GateResponse.newBuilder().setWorkspaceInstanceId(instance).setTransitionId(id).setEpoch(expected + 1).setState("OPEN").build();
        }
        synchronized MutationReceipt receipt(String id) {
            if (unavailableReceipts) throw Status.UNAVAILABLE.asRuntimeException();
            return receipts.get(id);
        }
        synchronized MutationReceipt apply(ApplyBatchRequest request) {
            assertThat(request.getGateEpoch()).isEqualTo(epoch); assertThat(gateState).isEqualTo("OPEN");
            assertThat(request.getExpectedRevision()).isEqualTo(revision);
            assertThat(request.getChangesCount()).isEqualTo(1); assertThat(request.getChanges(0).getPath()).isEqualTo(PATH);
            applies.incrementAndGet(); source = request.getChanges(0).getContent(); revision++;
            var receipt = MutationReceipt.newBuilder().setWorkspaceInstanceId(instance).setRequestId(request.getRequestId()).setOperationId(request.getOperationId())
                .setFingerprint("a".repeat(64)).setRevision(revision).setChanged(true).build();
            receipts.put(request.getRequestId(), receipt);
            if (loseApplyResponse) throw Status.UNAVAILABLE.asRuntimeException();
            return receipt;
        }
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
