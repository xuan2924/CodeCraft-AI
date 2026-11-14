package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeRequest;
import ai.codecraft.modules.copilot.repository.CopilotApprovalModeRepository;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.ExecutionInventory;
import ai.codecraft.workspace.v1.TestTaskSnapshot;
import ai.codecraft.workspace.v1.TestLogChunk;
import ai.codecraft.workspace.v1.TestEvidence;
import ai.codecraft.workspace.v1.ProblemIdentity;
import ai.codecraft.workspace.v1.StopTaskResponse;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.dto.StartTestRequest;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import com.google.protobuf.ByteString;
import ai.codecraft.workspace.v1.GateResponse;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import ai.codecraft.modules.copilot.repository.CopilotRetentionRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.copilot.repository.CopilotTestRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceMaintenanceRepository;
import ai.codecraft.modules.workspace.repository.DeletionRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.service.TestTaskStore;
import ai.codecraft.modules.workspace.service.TestTaskCoordinator;
import ai.codecraft.modules.workspace.service.TaskStreamPublisher;
import ai.codecraft.modules.workspace.service.WorkspaceMaintenanceStore;
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
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialToolCall;
import java.util.concurrent.Flow;
import dev.langchain4j.data.message.UserMessage;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerRequest;
import ai.codecraft.modules.copilot.repository.CopilotSteerRepository;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
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
    private final String database = "copilot_it_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate bootstrap;
    private final MutableClock clock = new MutableClock();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final CopilotProperties limits = new CopilotProperties(2, 262144, 1048576, 16, 3, Duration.ofSeconds(130));
    private final ModelCallProperties calls = new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 8, 8388608, 65536, 262144);
    private SqlSessionTemplate sql;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private CopilotTaskStore tasks;
    private CopilotTaskRepository repository;
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
    private CopilotProposalService proposalViews;
    private SandboxWorkspaceGateway sandbox;
    private WorkspaceWriteCoordinator writeCoordinator;
    private StorageCapacityService capacity;
    private CopilotToolExecutor executor;
    private TestTaskStore childExecutions;
    private TestTaskCoordinator testCoordinator;
    private CopilotTestLifecycle childLifecycle;
    private CopilotTestStore testRuns;
    private CopilotTestTools testTools;
    private WorkspaceMaintenanceStore maintenance;
    private CopilotApprovalModeService approvalModes;
    private CopilotAutoApproval automatic;
    private CopilotSteerService steers;

    @BeforeAll
    void migrate() throws Exception {
        String url = System.getenv("COPILOT_IT_JDBC_URL");
        int pathEnd = url.indexOf('?') < 0 ? url.length() : url.indexOf('?');
        int separator = url.lastIndexOf('/', pathEnd - 1);
        if (!url.startsWith("jdbc:postgresql://") || separator < "jdbc:postgresql://".length()) throw new IllegalArgumentException("Fixture requires a PostgreSQL database URL");
        bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, "fixture", "fixture"));
        bootstrap.execute("CREATE DATABASE " + database);
        var source = new DriverManagerDataSource(url.substring(0, separator + 1) + database + url.substring(pathEnd), "fixture", "fixture");
        jdbc = new JdbcTemplate(source); transactions = new DataSourceTransactionManager(source);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var config = new Configuration(); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        for (Class<?> mapper : List.of(IdentityRepository.class, ModelConnectionRepository.class, ModelConfigurationRepository.class,
            ExerciseRepository.class, ExerciseModelRepository.class, WorkspaceWriteRepository.class, StorageCapacityRepository.class,
            CopilotTaskRepository.class, CopilotRetentionRepository.class, CopilotProposalRepository.class, CopilotTestRepository.class, CopilotApprovalModeRepository.class, CopilotSteerRepository.class,
            TestTaskRepository.class, TaskStreamRepository.class, FinalizationRepository.class, WorkspaceMaintenanceRepository.class, DeletionRepository.class)) config.addMapper(mapper);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @AfterAll
    void eraseOwnedDatabase() {
        if (bootstrap != null) bootstrap.execute("DROP DATABASE IF EXISTS " + database);
    }

    @BeforeEach
    void seed() throws Exception {
        jdbc.execute("TRUNCATE copilot_steer,copilot_approval_change,copilot_approval_mode,copilot_test_run,copilot_proposal,copilot_tool,copilot_message,copilot_turn,copilot_task,workspace_task_event,workspace_task_stream,workspace_test_stop,workspace_test_task,workspace_mutation,workspace_transition,workspace_operation");
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
        maintenance = proxy(new WorkspaceMaintenanceStore(sql.getMapper(WorkspaceMaintenanceRepository.class), exercises,
            sql.getMapper(DeletionRepository.class), identity, clock, budget, data), WorkspaceMaintenanceStore.class);
        var executionProblems = mock(ProblemRepository.class);
        String problemId = exercises.find(exercise).problemVersionId();
        when(executionProblems.find(problemId)).thenReturn(new ProblemVersionEntity(problemId, "fixture", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "{}"));
        when(executionProblems.executionAvailable(problemId)).thenReturn(true);
        var streams = sql.getMapper(TaskStreamRepository.class);
        var publisher = proxy(new TaskStreamPublisher(streams, json, clock), TaskStreamPublisher.class);
        childExecutions = proxy(new TestTaskStore(identity, secrets, exercises, writes, sql.getMapper(TestTaskRepository.class), executionProblems, clock, json,
            streams, publisher, sql.getMapper(FinalizationRepository.class), budget, maintenance, capacityStore), TestTaskStore.class);
        childLifecycle = proxy(new CopilotTestLifecycle(sql.getMapper(CopilotTestRepository.class), childExecutions), CopilotTestLifecycle.class);
        var lifecycle = proxy(new ExerciseTransactionService(exercises, identity, mock(ProblemTransactionService.class), clock, secrets, coordination,
            new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), capacityStore, selection), ExerciseTransactionService.class);
        var operations = proxy(new WorkspaceAiOperationService(identity, exercises, writes, capacityStore, clock), WorkspaceAiOperationService.class);
        repository = sql.getMapper(CopilotTaskRepository.class);
        tasks = proxy(new CopilotTaskStore(repository, identity, secrets, lifecycle, coordination, selection, operations, clock, sql.getMapper(CopilotProposalRepository.class), childLifecycle, sql.getMapper(CopilotSteerRepository.class)), CopilotTaskStore.class);
        execution = proxy(new CopilotExecutionStore(repository, identity, coordination, selection, operations, limits, clock, new CopilotRequestFactory(), sql.getMapper(CopilotProposalRepository.class), secrets, childLifecycle, sql.getMapper(CopilotSteerRepository.class)), CopilotExecutionStore.class);
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 1, 0);
        sandbox = mock(SandboxWorkspaceGateway.class);
        when(sandbox.measureStorage(any())).thenAnswer(call -> measurement);
        capacity = new StorageCapacityService(capacityStore, sandbox, mock(WorkspaceMaintenanceCoordinator.class));
        approvalModes = new CopilotApprovalModeService(proxy(new CopilotApprovalModeStore(sql.getMapper(CopilotApprovalModeRepository.class),
            identity, secrets, lifecycle, coordination, operations, clock), CopilotApprovalModeStore.class), capacity);
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
        testCoordinator = new TestTaskCoordinator(childExecutions, sandbox, coordination, writeCoordinator);
        proposals = proxy(new CopilotProposalStore(sql.getMapper(CopilotProposalRepository.class), repository, tasks, execution, identity, secrets,
            lifecycle, coordination, writeStore, operations, codec, json, clock, sql.getMapper(CopilotApprovalModeRepository.class)), CopilotProposalStore.class);
        proposalViews = new CopilotProposalService(proposals, capacity, json);
        automatic = new CopilotAutoApproval(proposals, sql.getMapper(CopilotProposalRepository.class), capacity, codec, json);
        steers = new CopilotSteerService(proxy(new CopilotSteerStore(sql.getMapper(CopilotSteerRepository.class), repository, tasks,
            identity, coordination, operations, execution, clock), CopilotSteerStore.class), tasks, capacity, secrets);
        testRuns = proxy(new CopilotTestStore(sql.getMapper(CopilotTestRepository.class), repository, execution, tasks, childLifecycle,
            childExecutions, identity, lifecycle, coordination, clock), CopilotTestStore.class);
        testTools = new CopilotTestTools(testRuns, capacity, new CopilotTestResultFormatter(), json);
        executor = new CopilotToolExecutor(new CopilotReadTools(files, json), new CopilotProposalPreparation(files, codec, json), proposals, json, testTools);
        runner = new CopilotRunner(execution, capacity, driver, executor, limits, calls);
    }

    @Test
    void queuedSteersAreIdempotentOrderedAndOnlyEffectiveWhenIncludedInTheOriginalTaskRequest() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original goal", true, measurement);
        var first = steers.accept(SESSION, task.id(), CONTROL, "steer-one", new CopilotSteerRequest("First adjustment"));
        var second = steers.accept(SESSION, task.id(), CONTROL, "steer-two", new CopilotSteerRequest("Second adjustment"));
        assertThat(first.state()).isEqualTo("RECEIVED"); assertThat(second.state()).isEqualTo("RECEIVED");
        assertThat(steers.accept(SESSION, task.id(), CONTROL, "steer-one", new CopilotSteerRequest("First adjustment")).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> steers.accept(SESSION, task.id(), CONTROL, "steer-one", new CopilotSteerRequest("Different")))
            .hasMessage("COPILOT_IDEMPOTENCY_CONFLICT");
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            assertThat(request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).map(UserMessage::singleText))
                .containsExactly("Original goal", "First adjustment", "Second adjustment");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Adjusted response")).build(), 100);
        });
        var rebuilt = new CopilotRunner(execution, capacity, driver, executor, limits, calls);
        rebuilt.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(steers.list(SESSION, task.id(), 0).items()).extracting(value -> value.state()).containsExactly("APPLIED", "APPLIED");
        assertThat(steers.list(SESSION, task.id(), 0).items()).extracting(value -> value.id()).containsExactly(second.id(), first.id());
        assertThat(repository.latest(exercise).id()).isEqualTo(task.id());
    }

    @Test
    void steerInvalidatesOldApprovalAndPairsUnstartedToolsBeforeReplanning() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        steers.accept(SESSION, task.id(), CONTROL, "steer", new CopilotSteerRequest("Do not edit; explain the current code"));
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("INVALIDATED");
        assertThat(proposals.summary(SESSION, proposal).failureCode()).isEqualTo("STEER_SUPERSEDED");
        var late = proposals.decide(SESSION, proposal, CONTROL, "late-approval", new CopilotProposalDecisionRequest("APPROVE", null), measurement);
        assertThat(late.state()).isEqualTo("INVALIDATED"); assertThat(late.decisionType()).isNull();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            var results = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast).toList();
            assertThat(results).extracting(ToolExecutionResultMessage::id).containsExactly("before-proposal", "proposal-call", "after-proposal");
            assertThat(results.get(0).text()).contains("OK");
            assertThat(results.get(1).text()).contains("NOT_EXECUTED", "STEER_SUPERSEDED");
            assertThat(results.get(2).text()).contains("NOT_EXECUTED", "STEER_SUPERSEDED");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Explanation only")).build(), 100);
        });
        new CopilotRunner(execution, capacity, driver, executor, limits, calls).run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED"); assertThat(workspace.applies.get()).isZero();
        verify(files, times(2)).read(SESSION, exercise, ProposalWorkspace.PATH);
    }

    @Test
    void steerDuringModelResponseDoesNotMutateItsRequestOrExecuteItsOldTools() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        var calls = new AtomicInteger();
        var read = ToolExecutionRequest.builder().id("old-read").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            if (calls.getAndIncrement() == 0) {
                steers.accept(SESSION, task.id(), CONTROL, "during-model", new CopilotSteerRequest("New direction"));
                assertThat(request.messages().toString()).doesNotContain("New direction");
                assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("RECEIVED");
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(read))).build(), 100);
            }
            assertThat(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast))
                .singleElement().satisfies(result -> { assertThat(result.id()).isEqualTo("old-read"); assertThat(result.text()).contains("NOT_EXECUTED"); });
            assertThat(request.messages().toString()).contains("New direction");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Replanned")).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        verify(files, never()).read(anyString(), anyString(), anyString());
    }

    @Test
    void steerDuringPartialToolArgumentsWaitsForTheRealDriverCompletionBoundary() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        var factory = mock(ModelSdkClientFactory.class);
        var callsMade = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override public Flow.Publisher<ChatModelStreamingEvent> chat(ChatRequest request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private boolean requested;
                    @Override public void request(long demand) {
                        if (requested) return;
                        requested = true;
                        if (callsMade.getAndIncrement() == 0) {
                            var fragment = mock(PartialToolCall.class);
                            when(fragment.partialArguments()).thenReturn("{\"path\":");
                            subscriber.onNext(fragment);
                            steers.accept(SESSION, task.id(), CONTROL, "stream-steer", new CopilotSteerRequest("Different direction"));
                            assertThat(repository.latestTurn(task.id()).state()).isEqualTo("DISPATCHED");
                            verify(files, never()).read(anyString(), anyString(), anyString());
                            var tool = ToolExecutionRequest.builder().id("streamed-tool").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
                            subscriber.onNext(new CompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(List.of(tool))).build()));
                        } else {
                            assertThat(request.messages().toString()).contains("Different direction", "streamed-tool", "NOT_EXECUTED");
                            subscriber.onNext(new CompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Replanned")).build()));
                        }
                        subscriber.onComplete();
                    }
                    @Override public void cancel() { }
                });
            }
        };
        when(factory.create(any(), any(), any())).thenReturn(model);
        var streamLimits = new ModelCallProperties(Duration.ofSeconds(5), Duration.ofSeconds(5), 1, 8388608, 65536, 262144);
        try (var actualDriver = new ModelCallDriver(factory, streamLimits, clock)) {
            new CopilotRunner(execution, capacity, actualDriver, executor, limits, calls).run(task.id(), UUID.randomUUID().toString());
        }
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED"); assertThat(callsMade.get()).isEqualTo(2);
        verify(files, never()).read(anyString(), anyString(), anyString());
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("APPLIED");
    }

    @Test
    void steerDuringReadPreservesItsResultButNeverStartsTheRemainingTool() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        var first = ToolExecutionRequest.builder().id("started-read").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        var second = ToolExecutionRequest.builder().id("unstarted-read").name("view_file").arguments(first.arguments()).build();
        var snapshot = files.read(SESSION, exercise, "src/Main.java");
        when(files.read(SESSION, exercise, "src/Main.java")).thenAnswer(call -> {
            steers.accept(SESSION, task.id(), CONTROL, "during-read", new CopilotSteerRequest("Change direction"));
            return snapshot;
        });
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(first, second))).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Replanned")).build(), 100));
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        var results = repository.context(exercise).stream().filter(value -> value.kind().equals("TOOL")).toList();
        assertThat(results).hasSize(2); assertThat(results.getFirst().payload()).contains("started-read", "OK");
        assertThat(results.getLast().payload()).contains("unstarted-read", "NOT_EXECUTED", "STEER_SUPERSEDED");
        verify(files, times(2)).read(SESSION, exercise, "src/Main.java"); // Includes the fixture snapshot read above.
    }

    @Test
    void steerDuringProposalPreparationInvalidatesItBeforeAutomaticApprovalCanWin() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        when(files.read(SESSION, exercise, ProposalWorkspace.PATH)).thenAnswer(call -> {
            steers.accept(SESSION, task.id(), CONTROL, "during-preparation", new CopilotSteerRequest("Do not modify files"));
            return workspace.file();
        });
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(workspace.proposal()))).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("No edit performed")).build(), 100));
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(proposals.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("INVALIDATED");
        automatic.apply(task.id()); runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED"); assertThat(workspace.applies.get()).isZero();
    }

    @Test
    void steerDoesNotSkipAnUnknownWriteOrLoseTheAlreadyApprovedEffect() throws Exception {
        var workspace = new ProposalWorkspace(); workspace.loseApplyResponse = true; workspace.unavailableReceipts = true;
        var task = beginProposal(workspace, true);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        proposalViews.decide(SESSION, proposal, CONTROL, "approve", new CopilotProposalDecisionRequest("APPROVE", null));
        writeCoordinator.recover();
        steers.accept(SESSION, task.id(), CONTROL, "during-write", new CopilotSteerRequest("Explain the applied change"));
        proposals.recoverTask(task.id()); execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_WRITE");
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("RECEIVED");
        workspace.unavailableReceipts = false; writeCoordinator.recover(); proposals.recoverTask(task.id());
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED"); assertThat(workspace.applies.get()).isEqualTo(1);
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("APPLIED");
        assertThat(repository.context(exercise).stream().filter(value -> value.kind().equals("TOOL")).toList().getLast().payload())
            .contains("after-proposal", "NOT_EXECUTED");
    }

    @Test
    void steerWaitsForOriginalTestQuiescenceWithoutCancellingOrStartingAnotherTest() throws Exception {
        var sandboxTest = new TestExecution();
        var task = beginTest(true);
        var binding = sql.getMapper(CopilotTestRepository.class).pending(task.id());
        testCoordinator.observe(childExecutions.find(binding.testTaskId()));
        steers.accept(SESSION, task.id(), CONTROL, "during-test", new CopilotSteerRequest("Use the original result, no further tests"));
        sandboxTest.finish("FAILED", false);
        testCoordinator.observe(childExecutions.find(binding.testTaskId())); testTools.recoverTask(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_TEST");
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("RECEIVED");
        verify(sandbox, never()).stopSystemTask(any());
        sandboxTest.finish("FAILED", true);
        testCoordinator.observe(childExecutions.find(binding.testTaskId())); testTools.recoverTask(task.id());
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED"); assertThat(sandboxTest.starts.get()).isEqualTo(1);
        var results = repository.context(exercise).stream().filter(value -> value.kind().equals("TOOL")).toList();
        assertThat(results.getFirst().payload()).contains("FAILED", "test-first");
        assertThat(results.getLast().payload()).contains("NOT_EXECUTED", "test-repeat");
    }

    @Test
    void steerReceivedBeforeAFinalAnswerDispatchesAnotherRequestInTheSameTask() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        var calls = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) steers.accept(SESSION, task.id(), CONTROL, "new-goal", new CopilotSteerRequest("One more adjustment"));
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Answer")).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("QUEUED");
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("RECEIVED");
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED"); assertThat(calls.get()).isEqualTo(2);
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("APPLIED");
        assertThatThrownBy(() -> steers.accept(SESSION, task.id(), CONTROL, "after-end", new CopilotSteerRequest("Too late")))
            .hasMessage("COPILOT_STEER_NOT_ACTIVE");
    }

    @Test
    void stoppingBeforeSteerDispatchMarksItUnappliedAndDoesNotAddItToModelHistory() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        steers.accept(SESSION, task.id(), CONTROL, "not-applied", new CopilotSteerRequest("Never dispatched"));
        tasks.stop(SESSION, task.id(), "stop"); execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        var receipt = steers.accept(SESSION, task.id(), CONTROL, "not-applied", new CopilotSteerRequest("Never dispatched"));
        assertThat(receipt.state()).isEqualTo("NOT_APPLIED"); assertThat(receipt.reason()).isEqualTo("USER_STOP");
        assertThat(repository.context(exercise)).hasSize(1);
        verify(driver, never()).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void stopAfterSteerReceptionDuringAModelCallDoesNotDispatchItsAdjustment() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            steers.accept(SESSION, task.id(), CONTROL, "received", new CopilotSteerRequest("New direction"));
            tasks.stop(SESSION, task.id(), "stop");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Original response")).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("NOT_APPLIED");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void expirationBeforeSteerDispatchMarksItUnappliedInsteadOfQueuingANewRequest() throws Exception {
        jdbc.update("UPDATE workspace_exercise SET deadline_at=? WHERE id=?::uuid", Timestamp.from(clock.instant().plusSeconds(1)), exercise);
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            steers.accept(SESSION, task.id(), CONTROL, "received", new CopilotSteerRequest("New direction"));
            clock.now = clock.now.plusSeconds(2);
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Original response")).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("INTERRUPTED");
        var receipt = steers.list(SESSION, task.id(), 0).items().getFirst();
        assertThat(receipt.state()).isEqualTo("NOT_APPLIED"); assertThat(receipt.reason()).isEqualTo("EXERCISE_ENDED");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void steerQueueIsBoundedPagedAndRetainedUntilItsOriginalTaskIsStopped() {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        for (int index = 0; index < 32; index++) steers.accept(SESSION, task.id(), CONTROL, "queued-" + index, new CopilotSteerRequest("Same instruction"));
        assertThatThrownBy(() -> steers.accept(SESSION, task.id(), CONTROL, "overflow", new CopilotSteerRequest("New instruction")))
            .hasMessage("COPILOT_STEER_LIMIT");
        var page = steers.list(SESSION, task.id(), 0);
        assertThat(page.items()).hasSize(20); assertThat(page.nextCursor()).isNotNull();
        assertThat(steers.list(SESSION, task.id(), Long.parseLong(page.nextCursor())).items()).hasSize(12);
        assertThat(data.quiescent(exercise)).isFalse(); assertThat(data.retainedBytes(exercise)).isPositive();
        tasks.stop(SESSION, task.id(), "stop");
        assertThat(sql.getMapper(CopilotSteerRepository.class).pendingCount(task.id())).isZero();
        assertThat(data.quiescent(exercise)).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(sql.getMapper(CopilotSteerRepository.class).page(task.id(), 0)).isEmpty(); assertThat(data.retainedBytes(exercise)).isZero();
    }

    @Test
    void steerRequiresCurrentControlPrivateOwnershipAndTheOriginalTasksAuthorization() {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        var instruction = new CopilotSteerRequest("Direction");
        assertThatThrownBy(() -> steers.accept(SESSION, task.id(), "obsolete", "old", instruction)).hasMessage("STALE_CONTROL");
        String replacement = "d".repeat(43);
        jdbc.update("UPDATE workspace_control SET credential_hash=? WHERE exercise_id=?::uuid", IdentitySecrets.digest(replacement), exercise);
        assertThatThrownBy(() -> steers.accept(SESSION, task.id(), CONTROL, "old", instruction)).hasMessage("STALE_CONTROL");
        assertThat(steers.accept(SESSION, task.id(), replacement, "current", instruction).state()).isEqualTo("RECEIVED");
        var foreign = new SessionAuthorizationDTO(UUID.randomUUID().toString(), "USER", false, 1, clock.instant().plusSeconds(300));
        when(identity.authorizePrivateDelivery("foreign")).thenReturn(foreign);
        assertThatThrownBy(() -> steers.list("foreign", task.id(), 0)).hasMessage("COPILOT_NOT_FOUND");
        var admin = new SessionAuthorizationDTO(foreign.userId(), "ADMIN", false, 1, foreign.expiresAt());
        when(identity.authorizePrivateDelivery("admin")).thenReturn(admin);
        assertThatThrownBy(() -> steers.accept("admin", task.id(), replacement, "admin", instruction)).hasMessage("FORBIDDEN");
        var current = new SessionAuthorizationDTO(repository.find(task.id()).userId(), "USER", false, 1, clock.instant().plusSeconds(300));
        when(identity.authorization("fresh")).thenReturn(current);
        when(identity.authorizePrivateDelivery("fresh")).thenReturn(current);
        when(identity.authorizeMutation("fresh")).thenReturn(current);
        when(identity.tryAuthorizeMutation(SESSION)).thenReturn(null);
        assertThatThrownBy(() -> steers.accept("fresh", task.id(), replacement, "no-reauthorization", instruction)).hasMessage("COPILOT_STEER_NOT_ACTIVE");
        assertThat(steers.list("fresh", task.id(), 0).items()).hasSize(1);
    }

    @Test
    void invalidOrOverBudgetSteersDoNotInsertACommandOrBlockStopping() {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Original", true, measurement);
        for (String text : List.of(" ", "中".repeat(1366), "bad" + (char) 0, String.valueOf((char) 0xd800))) {
            assertThatThrownBy(() -> steers.accept(SESSION, task.id(), CONTROL, "invalid", new CopilotSteerRequest(text))).hasMessage("COPILOT_INVALID_REQUEST");
        }
        String unicode = "😀".repeat(1024);
        assertThat(steers.accept(SESSION, task.id(), CONTROL, "unicode", new CopilotSteerRequest(unicode)).text()).isEqualTo(unicode);
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 2, 268435456);
        assertThatThrownBy(() -> steers.accept(SESSION, task.id(), CONTROL, "full", new CopilotSteerRequest("Direction"))).hasMessage("STORAGE_BUDGET_EXCEEDED");
        assertThat(steers.list(SESSION, task.id(), 0).items()).hasSize(1);
        assertThat(tasks.stop(SESSION, task.id(), "stop").state()).isEqualTo("STOPPED");
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
        steers.accept(SESSION, task.id(), CONTROL, "late-direction", new CopilotSteerRequest("Different direction"));
        clock.now = clock.now.plusSeconds(130);
        execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(tasks.find(SESSION, task.id()).reason()).isEqualTo("MODEL_RESULT_UNKNOWN");
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().reason()).isEqualTo("MODEL_RESULT_UNKNOWN");
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
    void approvalModeDefaultsToAskAndCommandsAreVersionedAuditedAndReplayOnlyTheirOriginalResult() throws Exception {
        assertThat(approvalModes.current(SESSION, exercise).mode()).isEqualTo("ASK");
        assertThat(approvalModes.current(SESSION, exercise).version()).isEqualTo("0");
        var request = new CopilotApprovalModeRequest("AUTO", "0");
        var auto = approvalModes.change(SESSION, exercise, CONTROL, "auto", request);
        assertThat(auto.mode()).isEqualTo("AUTO"); assertThat(auto.version()).isEqualTo("1");
        approvalModes.change(SESSION, exercise, CONTROL, "ask", new CopilotApprovalModeRequest("ASK", "1"));
        assertThat(approvalModes.change(SESSION, exercise, "obsolete", "auto", request)).isEqualTo(auto);
        assertThat(approvalModes.current(SESSION, exercise).mode()).isEqualTo("ASK");
        assertThat(approvalModes.current(SESSION, exercise).history()).hasSize(2);
        assertThatThrownBy(() -> approvalModes.change(SESSION, exercise, CONTROL, "stale", request)).hasMessage("COPILOT_APPROVAL_MODE_CONFLICT");
        assertThatThrownBy(() -> approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("ASK", "0"))).hasMessage("COPILOT_IDEMPOTENCY_CONFLICT");
        var confirmation = approvalModes.change(SESSION, exercise, CONTROL, "confirm", new CopilotApprovalModeRequest("ASK", "2"));
        assertThat(confirmation.version()).isEqualTo("3");
        assertThat(approvalModes.current(SESSION, exercise).history()).extracting(value -> value.version()).containsExactly("3", "2", "1");
        verify(driver, never()).call(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void approvalModeCannotBypassControlCapacityOrExpiry() {
        var request = new CopilotApprovalModeRequest("AUTO", "0");
        assertThatThrownBy(() -> approvalModes.change(SESSION, exercise, "obsolete", "wrong", request)).hasMessage("STALE_CONTROL");
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 2, 268435456);
        assertThatThrownBy(() -> approvalModes.change(SESSION, exercise, CONTROL, "full", request)).hasMessage("STORAGE_BUDGET_EXCEEDED");
        assertThat(approvalModes.current(SESSION, exercise).version()).isEqualTo("0");
        assertThat(approvalModes.current(SESSION, exercise).history()).isEmpty();
        clock.now = clock.now.plusSeconds(1801);
        assertThatThrownBy(() -> approvalModes.change(SESSION, exercise, CONTROL, "expired", request)).hasMessage("INVALID_STATE");
    }

    @Test
    void approvalModeRejectsMalformedVersionsOtherOwnersAndAdministrativeActors() {
        for (var input : List.of(new CopilotApprovalModeRequest("SHELL", "0"), new CopilotApprovalModeRequest(null, "0"),
            new CopilotApprovalModeRequest("AUTO", "01"), new CopilotApprovalModeRequest("AUTO", "-1"),
            new CopilotApprovalModeRequest("AUTO", "9223372036854775808"), new CopilotApprovalModeRequest("AUTO", "9223372036854775807"))) {
            assertThatThrownBy(() -> approvalModes.change(SESSION, exercise, CONTROL, "bad", input)).hasMessage("COPILOT_APPROVAL_MODE_INVALID");
        }
        var other = new SessionAuthorizationDTO(UUID.randomUUID().toString(), "USER", false, 1, clock.instant().plusSeconds(300));
        when(identity.authorization("other")).thenReturn(other);
        when(identity.authorizePrivateDelivery("other")).thenReturn(other);
        when(identity.authorizeMutation("other")).thenReturn(other);
        assertThatThrownBy(() -> approvalModes.current("other", exercise)).hasMessage("RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> approvalModes.change("other", exercise, CONTROL, "other", new CopilotApprovalModeRequest("AUTO", "0"))).hasMessage("RESOURCE_NOT_FOUND");
        var admin = new SessionAuthorizationDTO(other.userId(), "ADMIN", false, 1, clock.instant().plusSeconds(300));
        when(identity.authorizePrivateDelivery(SESSION)).thenReturn(admin);
        assertThatThrownBy(() -> approvalModes.current(SESSION, exercise)).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> approvalModes.change(SESSION, exercise, CONTROL, "admin", new CopilotApprovalModeRequest("AUTO", "0"))).hasMessage("FORBIDDEN");
        assertThat(sql.getMapper(CopilotApprovalModeRepository.class).current(exercise)).isNull();
        verify(sandbox, never()).measureStorage(any());
    }

    @Test
    void competingModeCommandsHaveOneVersionWinnerAndAnImmutableAudit() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> raceMode("AUTO", barrier));
            var second = workers.submit(() -> raceMode("ASK", barrier));
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("1", "COPILOT_APPROVAL_MODE_CONFLICT");
        }
        assertThat(approvalModes.current(SESSION, exercise).version()).isEqualTo("1");
        assertThat(approvalModes.current(SESSION, exercise).history()).hasSize(1);
    }

    @Test
    void modeAuditIsBoundedForDeliveryAndIncludedInExerciseDeletion() {
        for (int version = 0; version < 23; version++) approvalModes.change(SESSION, exercise, CONTROL, "mode-" + version,
            new CopilotApprovalModeRequest(version % 2 == 0 ? "AUTO" : "ASK", Integer.toString(version)));
        assertThat(approvalModes.current(SESSION, exercise).history()).hasSize(20);
        assertThat(approvalModes.current(SESSION, exercise).history().getFirst().version()).isEqualTo("23");
        assertThat(data.retainedBytes(exercise)).isPositive();
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(sql.getMapper(CopilotApprovalModeRepository.class).current(exercise)).isNull();
        assertThat(sql.getMapper(CopilotApprovalModeRepository.class).history(exercise)).isEmpty();
        assertThat(data.retainedBytes(exercise)).isZero();
    }

    @Test
    void proposalHistoryOrdersNumericSequencesAcrossDigitBoundaries() throws Exception {
        new ProposalWorkspace();
        jdbc.execute("ALTER SEQUENCE copilot_proposal_sequence_seq RESTART WITH 8");
        var requests = new ArrayList<ToolExecutionRequest>();
        for (int index = 0; index < 5; index++) requests.add(ToolExecutionRequest.builder().id("history-" + index).name("propose_changes")
            .arguments("{\"expected_revision\":\"1\",\"changes\":[{\"kind\":\"UPDATE\",\"path\":\"" + ProposalWorkspace.PATH
                + "\",\"content\":\"class Main { int version = " + index + "; }\"}]}").build());
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(requests)).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Done")).build(), 100));
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Compare alternatives", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        for (int index = 0; index < 5; index++) {
            String proposal = sql.getMapper(CopilotProposalRepository.class).pending(task.id()).id();
            proposals.decide(SESSION, proposal, CONTROL, "reject-" + index, new CopilotProposalDecisionRequest("REJECT", "Compare next"), null);
            runner.run(task.id(), UUID.randomUUID().toString());
        }
        assertThat(proposals.list(SESSION, task.id(), 0).items()).extracting(value -> value.sequence()).containsExactly("12", "11", "10", "9", "8");
        assertThat(proposals.list(SESSION, task.id(), 10).items()).extracting(value -> value.sequence()).containsExactly("9", "8");
    }

    @Test
    void autoProposalCapturesTheModeAtRegistrationRatherThanModelDispatch() throws Exception {
        var workspace = new ProposalWorkspace();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            approvalModes.change(SESSION, exercise, CONTROL, "during-generation", new CopilotApprovalModeRequest("AUTO", "0"));
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(workspace.proposal()))).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Change the class", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        var proposal = proposals.list(SESSION, task.id(), 0).items().getFirst();
        assertThat(proposal.mode()).isEqualTo("AUTO");
        assertThat(proposal.modeVersion()).isEqualTo("1");
        assertThat(proposal.state()).isEqualTo("PENDING");
        assertThat(repository.find(task.id()).workerId()).isNull();
    }

    @Test
    void autoApprovalUsesTheBoundedWorkerAndOriginalWriteReceiptWithoutBrowserCredential() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace(); workspace.loseApplyResponse = true;
        var task = beginProposal(workspace, true);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        try (var coordinator = new CopilotCoordinator(repository, execution, runner, limits, clock, proposals, testTools, automatic)) {
            coordinator.dispatch();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_WRITE"));
        }
        assertThat(proposals.summary(SESSION, proposal).decisionType()).isEqualTo("APPROVE");
        assertThat(workspace.applies.get()).isZero();
        writeCoordinator.recover(); proposals.recoverTask(task.id());
        writeCoordinator.recover(); proposals.recoverTask(task.id());
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("APPLIED");
        assertThat(workspace.applies.get()).isEqualTo(1);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void switchingToAutoNeverApprovesAnAlreadyRegisteredAskProposal() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        assertThat(automatic.pending()).doesNotContain(task.id());
        automatic.apply(task.id());
        var proposal = proposals.list(SESSION, task.id(), 0).items().getFirst();
        assertThat(proposal.mode()).isEqualTo("ASK"); assertThat(proposal.modeVersion()).isEqualTo("0");
        assertThat(proposal.state()).isEqualTo("PENDING"); assertThat(proposal.decisionType()).isNull();
        assertThat(workspace.applies.get()).isZero();
    }

    @Test
    void registeredAutoAuthorizationSurvivesSwitchingBackToAskButCannotBeDecidedManually() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        approvalModes.change(SESSION, exercise, CONTROL, "ask", new CopilotApprovalModeRequest("ASK", "1"));
        assertThatThrownBy(() -> proposalViews.decide(SESSION, proposal, CONTROL, "manual", new CopilotProposalDecisionRequest("APPROVE", null)))
            .hasMessage("COPILOT_INVALID_STATE");
        automatic.apply(task.id()); automatic.apply(task.id());
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("APPLYING");
        assertThat(proposals.summary(SESSION, proposal).modeVersion()).isEqualTo("1");
        writeCoordinator.recover(); proposals.recoverTask(task.id());
        assertThat(workspace.applies.get()).isEqualTo(1);
        assertThat(approvalModes.current(SESSION, exercise).mode()).isEqualTo("ASK");
    }

    @Test
    void autoBaselineConflictInvalidatesTheOldProposalWithoutMerging() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        sql.getMapper(WorkspaceWriteRepository.class).observed(exercise, 2, "[\"" + ProposalWorkspace.PATH + "\"]");
        automatic.apply(task.id());
        var proposal = proposals.list(SESSION, task.id(), 0).items().getFirst();
        assertThat(proposal.state()).isEqualTo("INVALIDATED"); assertThat(proposal.failureCode()).isEqualTo("REVISION_CONFLICT");
        assertThat(proposal.decisionType()).isNull(); assertThat(workspace.applies.get()).isZero();
    }

    @Test
    void autoMeterOutageRetainsTheOriginalProposalWithoutInventingARejectionOrCallingTheModel() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        when(sandbox.measureStorage(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        assertThatThrownBy(() -> automatic.apply(task.id())).hasMessage("STORAGE_METER_UNAVAILABLE");
        var original = proposals.list(SESSION, task.id(), 0).items().getFirst();
        assertThat(original.state()).isEqualTo("PENDING"); assertThat(original.decisionType()).isNull();
        assertThat(repository.find(task.id()).state()).isEqualTo("WAITING_APPROVAL"); assertThat(workspace.applies.get()).isZero();
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
        doReturn(measurement).when(sandbox).measureStorage(any());
        automatic.apply(task.id());
        var recovered = proposals.list(SESSION, task.id(), 0).items().getFirst();
        assertThat(recovered.id()).isEqualTo(original.id()); assertThat(recovered.state()).isEqualTo("APPLYING");
    }

    @Test
    void autoCapacityFailurePreservesItsDiffAndDoesNotAdmitAWrite() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, false);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 2, 268435456);
        automatic.apply(task.id());
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("FAILED");
        assertThat(proposals.summary(SESSION, proposal).failureCode()).isEqualTo("STORAGE_BUDGET_EXCEEDED");
        assertThat(proposalViews.detail(SESSION, proposal).files()).isNotEmpty();
        assertThat(sql.getMapper(CopilotProposalRepository.class).find(proposal).operationId()).isNull();
        assertThat(workspace.applies.get()).isZero();
        tasks.stop(SESSION, task.id(), "stop"); execution.recover(task.id());
        assertThat(repository.find(task.id()).state()).isEqualTo("STOPPED");
    }

    @Test
    void stoppingBeforeAutoAdmissionCancelsWithoutApplying() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        tasks.stop(SESSION, task.id(), "stop");
        automatic.apply(task.id()); execution.recover(task.id());
        assertThat(proposals.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("CANCELLED");
        assertThat(repository.find(task.id()).state()).isEqualTo("STOPPED");
        assertThat(workspace.applies.get()).isZero();
    }

    @Test
    void stopAfterAutoApplicationPreservesTheConfirmedRevisionWithoutAnotherModelCall() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        automatic.apply(task.id()); writeCoordinator.recover();
        tasks.stop(SESSION, task.id(), "stop"); proposals.recoverTask(task.id()); execution.recover(task.id());
        var proposal = sql.getMapper(CopilotProposalRepository.class).page(task.id(), 0).getFirst();
        assertThat(proposal.state()).isEqualTo("APPLIED"); assertThat(proposal.resultRevision()).isEqualTo("2");
        assertThat(repository.find(task.id()).state()).isEqualTo("STOPPED"); assertThat(workspace.applies.get()).isEqualTo(1);
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void expiredAutoProposalCannotApplyOrWakeTheModel() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        clock.now = clock.now.plusSeconds(1801);
        automatic.apply(task.id()); execution.recover(task.id());
        assertThat(proposals.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("CANCELLED");
        assertThat(repository.find(task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(workspace.applies.get()).isZero();
    }

    @Test
    void autoAuthorizationLossCommitsCancellationThroughTheRealIdentityProxy() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        invalidateOriginalAuthorizationUsingRealProxy();
        automatic.apply(task.id()); execution.recover(task.id());
        assertThat(sql.getMapper(CopilotProposalRepository.class).page(task.id(), 0).getFirst().state()).isEqualTo("CANCELLED");
        assertThat(repository.find(task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(repository.find(task.id()).reason()).isEqualTo("AUTHORIZATION_ENDED");
        assertThat(workspace.applies.get()).isZero();
    }

    @Test
    void approvedProposalResumesTheOriginalBatchWithoutRegeneratingOrReapplyingIt() throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        assertThat(proposalViews.detail(SESSION, proposal).files().getFirst().before()).isEqualTo("class Main {}");
        assertThat(tasks.messages(SESSION, exercise, -1)).hasSize(3);
        runner = new CopilotRunner(execution, capacity, driver, executor, limits, calls);
        var approve = new CopilotProposalDecisionRequest("APPROVE", null);
        assertThat(proposals.decide(SESSION, proposal, CONTROL, "approve", approve, measurement).state()).isEqualTo("APPLYING");
        assertThat(proposals.decide(SESSION, proposal, "obsolete", "approve", approve, measurement).state()).isEqualTo("APPLYING");
        writeCoordinator.recover(); proposals.recoverTask(task.id());
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("APPLIED");
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
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("FAILED");
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
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("APPLIED");
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
        assertThatThrownBy(() -> proposals.evidence("foreign", proposal)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> proposals.decide("foreign", proposal, CONTROL, "approve", approve, measurement)).isInstanceOf(BusinessException.class);
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("PENDING");
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
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("PENDING");
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
        assertThat(proposals.summary(SESSION, proposal).state()).isEqualTo("APPLIED");
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
            assertThat(proposalViews.detail(SESSION, proposal).files().getFirst().after()).contains("42");
            proposals.decide(SESSION, proposal, CONTROL, "live-approve", new CopilotProposalDecisionRequest("APPROVE", null), measurement);
            writeCoordinator.recover(); proposals.recoverTask(task.id());
            runner.run(task.id(), UUID.randomUUID().toString());
            assertThat(tasks.find(SESSION, task.id()).state()).as("live continuation reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("SUCCEEDED");
            assertThat(workspace.applies.get()).isEqualTo(1);
            assertThat(tasks.messages(SESSION, exercise, -1).getLast().payload()).contains("42");
            assertThat(proposals.summary(SESSION, proposal).resultRevision()).isEqualTo("2");
            var samples = jdbc.queryForList("SELECT input_tokens,output_tokens,total_tokens,usage_payload FROM copilot_turn WHERE task_id=?::uuid ORDER BY ordinal", task.id());
            for (var sample : samples) assertThat(sample.get("usage_payload")).isNotNull();
            System.out.println("DeepSeek live proposal usage (Go is a deterministic fixture; no credentials or request bodies): " + samples);
        } finally { connections.delete(SESSION, connection.id(), "live-cleanup", connection.version()); }
    }

    @Test
    void testWaitReleasesWorkerAndLostStartResponseRecoversWithoutStartingAgain() throws Exception {
        var sandboxTest = new TestExecution();
        sandboxTest.loseStart = true;
        var task = beginTest(true);
        var binding = sql.getMapper(CopilotTestRepository.class).pending(task.id());
        assertThat(repository.find(task.id()).workerId()).isNull();
        assertThatThrownBy(() -> testCoordinator.observe(childExecutions.find(binding.testTaskId()))).isInstanceOf(StatusRuntimeException.class);
        testTools.recoverTask(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_TEST");
        sandboxTest.finish("FAILED", true);
        testCoordinator.observe(childExecutions.find(binding.testTaskId()));
        testTools.recoverTask(task.id());
        testTools.recoverTask(task.id());
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(sandboxTest.starts.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_test_run WHERE task_id=?::uuid", Integer.class, task.id())).isEqualTo(1);
        var results = repository.context(exercise).stream().filter(message -> message.kind().equals("TOOL")).toList();
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().payload()).contains("FAILED", "TEST_RESULT");
        assertThat(results.getLast().payload()).contains("reused\\\":true");
        verify(driver, times(2)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void parentStopBeforeChildDispatchDoesNotStartAndWaitsForItsExactCancellationReceipt() throws Exception {
        var sandboxTest = new TestExecution();
        var task = beginTest(false);
        var binding = sql.getMapper(CopilotTestRepository.class).pending(task.id());
        tasks.stop(SESSION, task.id(), "stop");
        testCoordinator.observe(childExecutions.find(binding.testTaskId()));
        execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPING");
        assertThat(sandboxTest.starts.get()).isZero();
        testCoordinator.recover();
        testTools.recoverTask(task.id());
        execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(childExecutions.find(binding.testTaskId()).quiescent()).isTrue();
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void stopAcceptanceIsNotProcessExitAndResultVersionMustStillMatchAtCommit() throws Exception {
        var sandboxTest = new TestExecution();
        var task = beginTest(false);
        var binding = sql.getMapper(CopilotTestRepository.class).pending(task.id());
        testCoordinator.observe(childExecutions.find(binding.testTaskId()));
        tasks.stop(SESSION, task.id(), "stop");
        sandboxTest.finish("STOPPED", false);
        testCoordinator.recover();
        testTools.recoverTask(task.id());
        execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPING");
        sandboxTest.finish("STOPPED", true);
        testCoordinator.observe(childExecutions.find(binding.testTaskId()));
        var captured = testRuns.recovery(task.id());
        sandboxTest.version++;
        testCoordinator.observe(childExecutions.find(binding.testTaskId()));
        testRuns.complete(captured, json.writeValueAsString(new CopilotTestResultFormatter().format(json.readValue(captured.evidence().payload(), TestTaskResponse.class), false)));
        assertThat(sql.getMapper(CopilotTestRepository.class).waiting(task.id())).isTrue();
        testTools.recoverTask(task.id());
        execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(sandboxTest.starts.get()).isEqualTo(1);
    }

    @Test
    void anIndependentManualTestProducesBusyWithoutBeingAdoptedOrStopped() throws Exception {
        var sandboxTest = new TestExecution();
        var manual = childExecutions.accept(SESSION, exercise, "manual", CONTROL, new StartTestRequest("1", "base"), measurement);
        var task = beginTestAttempt(false);
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(repository.context(exercise).stream().filter(message -> message.kind().equals("TOOL")).findFirst().orElseThrow().payload()).contains("TEST_SLOT_BUSY");
        tasks.stop(SESSION, task.id(), "stop");
        assertThat(sql.getMapper(TestTaskRepository.class).acceptedStop(manual.id())).isNull();
        assertThat(sql.getMapper(CopilotTestRepository.class).recent(task.id())).isEmpty();
        assertThat(sandboxTest.starts.get()).isZero();
    }

    @Test
    void revokedAuthorizationStopsOnlyTheBoundChildAndNeverResumesTheModel() throws Exception {
        new TestExecution();
        var task = beginTest(false);
        invalidateOriginalAuthorizationUsingRealProxy();
        testTools.recoverTask(task.id());
        testCoordinator.recover();
        testTools.recoverTask(task.id());
        execution.recover(task.id());
        assertThat(repository.find(task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(repository.find(task.id()).reason()).isEqualTo("AUTHORIZATION_ENDED");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void completedChildEvidenceIsProtectedUntilTheParentToolMessageIsCommitted() throws Exception {
        var sandboxTest = new TestExecution();
        var task = beginTest(false);
        var child = sql.getMapper(CopilotTestRepository.class).pending(task.id());
        sandboxTest.finish("PASSED", true);
        testCoordinator.observe(childExecutions.find(child.testTaskId()));
        jdbc.update("""
            INSERT INTO workspace_test_task(id,execution_instance_id,exercise_id,workspace_instance_id,revision,gate_epoch,not_after,target,state,quiescent,snapshot)
            SELECT gen_random_uuid(),gen_random_uuid(),exercise_id,workspace_instance_id,revision,gate_epoch,not_after,target,state,true,
                jsonb_set(snapshot,'{finishedAt}',to_jsonb(?::text))
            FROM workspace_test_task CROSS JOIN generate_series(1,21) WHERE id=?::uuid
            """, clock.instant().plusSeconds(1).toString(), child.testTaskId());
        assertThat(maintenance.pruningPlan(exercise)).noneMatch(record -> record.taskId().equals(child.testTaskId()));
        assertThat(sql.getMapper(TestTaskRepository.class).retired(child.testTaskId())).isFalse();
        testTools.recoverTask(task.id());
        assertThat(maintenance.pruningPlan(exercise)).anyMatch(record -> record.taskId().equals(child.testTaskId()));
        assertThat(sql.getMapper(CopilotTestRepository.class).forTool(child.toolId()).resultPayload()).contains("test log");
    }

    @Test
    void expiredExerciseNeverWakesTheModelAfterAChildResultAndPairsRemainingCalls() throws Exception {
        var sandboxTest = new TestExecution();
        var task = beginTest(true);
        var child = sql.getMapper(CopilotTestRepository.class).pending(task.id());
        testCoordinator.observe(childExecutions.find(child.testTaskId()));
        clock.now = clock.now.plusSeconds(3601);
        sandboxTest.finish("PASSED", true);
        testCoordinator.observe(childExecutions.find(child.testTaskId()));
        testTools.recoverTask(task.id());
        assertThat(repository.find(task.id()).state()).isEqualTo("STOPPING");
        runner.run(task.id(), UUID.randomUUID().toString());
        execution.recover(task.id());
        assertThat(repository.find(task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(repository.find(task.id()).reason()).isEqualTo("EXERCISE_ENDED");
        assertThat(repository.context(exercise).stream().filter(message -> message.kind().equals("TOOL"))).hasSize(2);
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    private CopilotTaskResponse beginTest(boolean repeat) throws Exception {
        var task = beginTestAttempt(repeat);
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_TEST");
        return task;
    }

    private CopilotTaskResponse beginTestAttempt(boolean repeat) throws Exception {
        var first = ToolExecutionRequest.builder().id("test-first").name("run_tests").arguments("{\"expected_revision\":\"1\",\"target\":\"base\"}").build();
        var second = ToolExecutionRequest.builder().id("test-repeat").name("run_tests").arguments(first.arguments()).build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(repeat ? List.of(first, second) : List.of(first))).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Confirmed test result received.")).build(), 100));
        var task = tasks.accept(SESSION, exercise, CONTROL, "test-start", "Run the base tests", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        return task;
    }

    private final class TestExecution {
        final AtomicInteger starts = new AtomicInteger();
        String state = "RUNNING";
        boolean quiescent;
        boolean loseStart;
        long version = 1;
        TestExecution() {
            when(sandbox.inventory(anyString(), anyString())).thenReturn(ExecutionInventory.newBuilder().setHostEpoch("A".repeat(26)).build());
            when(sandbox.startTest(any(), any())).thenAnswer(call -> {
                starts.incrementAndGet();
                if (loseStart) throw Status.UNAVAILABLE.asRuntimeException();
                return snapshot(call.getArgument(0));
            });
            when(sandbox.task(any())).thenAnswer(call -> snapshot(call.getArgument(0)));
            when(sandbox.queryStop(any())).thenReturn(StopTaskResponse.getDefaultInstance());
            when(sandbox.stopSystemTask(any())).thenAnswer(call -> {
                TestTaskEntity task = call.getArgument(0);
                var response = StopTaskResponse.newBuilder().setHostEpoch("A".repeat(26));
                return task.dispatched() ? response.setSnapshot(snapshot(task)).build() : response.setCancelledBeforeRegistration(true).build();
            });
        }
        void finish(String result, boolean quiet) { state = result; quiescent = quiet; version++; }
        TestTaskSnapshot snapshot(TestTaskEntity task) {
            var problem = childExecutions.problem(task);
            var value = TestTaskSnapshot.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id()).setExecutionInstanceId(task.executionInstanceId())
                .setWorkspaceInstanceId(task.workspaceInstanceId()).setRevision(task.revision()).setTarget(task.target()).setState(state).setReason("fixture_result")
                .setStartedAtUnixMillis(clock.instant().minusSeconds(1).toEpochMilli()).setQuiescent(quiescent)
                .setCleanup(quiescent ? "SUCCEEDED" : "PENDING").setCopyCleanup(quiescent ? "SUCCEEDED" : "PENDING").setStateVersion(version).setStreamId("S".repeat(26))
                .setProblem(ProblemIdentity.newBuilder().setProblemId(problem.problemId()).setVersion(problem.version()).setPackageSha256(problem.packageSha256())
                    .setExecutionImageDigest(problem.executionImageDigest()).setPlatform(problem.platform()))
                .addLogs(TestLogChunk.newBuilder().setChannel("stdout").setData(ByteString.copyFromUtf8("test log")));
            if (!state.equals("RUNNING")) value.setFinishedAtUnixMillis(clock.instant().toEpochMilli());
            if (state.equals("PASSED") || state.equals("FAILED")) value.setEvidence(TestEvidence.newBuilder().setKind("SUREFIRE_XML").setSha256("e".repeat(64)).setBytes(100));
            return value.build();
        }
    }

    private String raceMode(String mode, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try { return approvalModes.change(SESSION, exercise, CONTROL, "race-" + mode, new CopilotApprovalModeRequest(mode, "0")).version(); }
        catch (BusinessException rejected) { return rejected.errorCode().code(); }
    }

    private void invalidateOriginalAuthorizationUsingRealProxy() {
        var actual = proxy(new IdentityTransactionService(sql.getMapper(IdentityRepository.class), clock, mock(IdentityRevocationNotifier.class),
            mock(RevocationIntentRepository.class), mock(IdentityRevocationBarrier.class)), IdentityTransactionService.class);
        // The fixture grant has no real session row: exercise actual transactional denial, not a mock-only throw.
        when(identity.authorization(SESSION)).thenAnswer(call -> actual.authorization(SESSION));
        when(identity.tryAuthorizeMutation(SESSION)).thenAnswer(call -> actual.tryAuthorizeMutation(SESSION));
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
