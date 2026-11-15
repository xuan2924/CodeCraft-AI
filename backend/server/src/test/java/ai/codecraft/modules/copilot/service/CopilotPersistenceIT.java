package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.repository.CopilotUsageRepository;
import ai.codecraft.modules.skills.repository.SkillSnapshotRepository;
import ai.codecraft.modules.skills.repository.SkillRepository;
import ai.codecraft.modules.skills.service.SkillService;
import ai.codecraft.modules.skills.service.SkillStore;
import ai.codecraft.modules.skills.service.SkillPackageParser;
import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import ai.codecraft.modules.skills.model.dto.SkillFileDTO;
import ai.codecraft.modules.skills.service.SkillSnapshots;
import ai.codecraft.modules.skills.service.SkillExerciseData;
import ai.codecraft.modules.modelaccess.service.ModelUsageAccounting;
import ai.codecraft.modules.modelaccess.service.ModelUsageNormalizer;
import ai.codecraft.modules.modelaccess.service.ModelPriceCatalog;
import ai.codecraft.modules.modelaccess.service.ModelCostCalculator;
import ai.codecraft.modules.copilot.config.CopilotContextProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotContextPreparedDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotForkRequest;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
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
import ai.codecraft.modules.copilot.repository.CopilotFollowupRepository;
import ai.codecraft.modules.copilot.repository.CopilotBtwRepository;
import ai.codecraft.modules.copilot.repository.CopilotDelegationRepository;
import ai.codecraft.modules.copilot.model.dto.CopilotBtwRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotReferenceSelectionDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotStartRequest;
import ai.codecraft.modules.copilot.model.entity.CopilotFollowupEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.repository.CopilotConversationRepository;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.copilot.repository.CopilotTestRepository;
import ai.codecraft.modules.copilot.repository.CopilotCommandRepository;
import ai.codecraft.modules.workspace.repository.CommandTaskRepository;
import ai.codecraft.modules.workspace.service.CommandTaskStore;
import ai.codecraft.modules.workspace.service.CommandTaskCoordinator;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.workspace.v1.CommandSnapshot;
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
import ai.codecraft.modules.workspace.service.DeletionStore;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import ai.codecraft.modules.workspace.service.ExerciseService;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.TestTaskService;
import ai.codecraft.modules.workspace.service.CommandTaskService;
import ai.codecraft.modules.workspace.service.ProblemTransactionService;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import ai.codecraft.modules.workspace.service.StorageCapacityStore;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import ai.codecraft.modules.workspace.service.WorkspaceHistoryLedger;
import ai.codecraft.modules.workspace.service.WorkspaceHistoryData;
import ai.codecraft.modules.workspace.repository.WorkspaceHistoryRepository;
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
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionDispatchDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionReceiptDTO;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerRequest;
import ai.codecraft.modules.copilot.repository.CopilotSteerRepository;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.CountDownLatch;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private CopilotContextService contexts;
    private CopilotContextPreparation contextPreparation;
    private SkillSnapshots skillSnapshots;
    private SkillService personalSkills;
    private CopilotSkillContext skillContext;
    private CopilotEvidenceStore evidenceStore;
    private CopilotCompactionStore compactions;
    private CopilotCompactionExecution summaryExecution;
    private CopilotCompactionRunner summaryRunner;
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
    private CopilotWebTools webTools;
    private CopilotReferences references;
    private CopilotBtwStore btwStore;
    private CopilotDelegationService delegations;
    private CopilotBtwService btw;
    private TestTaskStore childExecutions;
    private CommandTaskStore childCommands;
    private CopilotCommandLifecycle commandLifecycle;
    private CopilotCommandStore commandStore;
    private CopilotCommandService commandTools;
    private TestTaskCoordinator testCoordinator;
    private CopilotTestLifecycle childLifecycle;
    private CopilotUsageService usage;
    private CopilotTestStore testRuns;
    private CopilotTestTools testTools;
    private WorkspaceMaintenanceStore maintenance;
    private CopilotConversationStore conversations;
    private CopilotApprovalModeService approvalModes;
    private CopilotAutoApproval automatic;
    private CopilotSteerService steers;
    private CopilotFollowupStore followups;
    private CopilotFollowupService followupService;

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
            CopilotTaskRepository.class, CopilotConversationRepository.class, CopilotFollowupRepository.class, CopilotBtwRepository.class, CopilotDelegationRepository.class, CopilotRetentionRepository.class, CopilotProposalRepository.class, CopilotTestRepository.class, CopilotCommandRepository.class, CommandTaskRepository.class, CopilotApprovalModeRepository.class, CopilotSteerRepository.class, CopilotCompactionRepository.class, CopilotUsageRepository.class, SkillSnapshotRepository.class, SkillRepository.class,
            TestTaskRepository.class, TaskStreamRepository.class, FinalizationRepository.class, WorkspaceMaintenanceRepository.class, DeletionRepository.class, WorkspaceHistoryRepository.class)) config.addMapper(mapper);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @AfterAll
    void eraseOwnedDatabase() {
        if (bootstrap != null) bootstrap.execute("DROP DATABASE IF EXISTS " + database);
    }

    @BeforeEach
    void seed() throws Exception {
        jdbc.execute("TRUNCATE copilot_btw,copilot_followup,copilot_followup_resume,copilot_command,workspace_command,copilot_skill_snapshot,personal_skill_command,personal_skill,personal_skill_version,copilot_context,copilot_compaction_call,copilot_compaction,copilot_steer,copilot_approval_change,copilot_approval_mode,copilot_test_run,copilot_proposal,copilot_tool,copilot_message,copilot_turn,copilot_task,workspace_task_event,workspace_task_stream,workspace_test_stop,workspace_test_task,workspace_mutation,workspace_transition,workspace_operation");
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
        var historyRepository = sql.getMapper(WorkspaceHistoryRepository.class);
        data = new ExerciseDataService(List.of(new CopilotExerciseData(sql.getMapper(CopilotRetentionRepository.class)), new SkillExerciseData(sql.getMapper(SkillSnapshotRepository.class)), new WorkspaceHistoryData(historyRepository)));
        var history = new WorkspaceHistoryLedger(historyRepository, data, clock);
        var coordination = proxy(new WorkspaceCoordinationStore(writes, exercises, json, history), WorkspaceCoordinationStore.class);
        var invocations = proxy(new ModelInvocationService(identity, connectionRepository, modelRepository, cipher), ModelInvocationService.class);
        selection = proxy(new ExerciseModelService(sql.getMapper(ExerciseModelRepository.class), exercises, identity, coordination, invocations,
            mock(ModelCheckService.class), clock, secrets), ExerciseModelService.class);
        selection.select(SESSION, exercise, CONTROL, "select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
        var alternate = models.create(SESSION, connection.id(), "alternate", new ModelConfigurationRequest("Alternate", "alternate", 1000000, 4096, ModelThinkingMode.OFF, null, null, null, null));
        alternateModelId = alternate.id();
        switchSelectedModel = () -> selection.select(SESSION, exercise, CONTROL, "switch", new ModelInvocationReferenceDTO(connection.id(), alternate.id(), 1, 1));
        var budget = new StorageProperties(536870912, 268435456, 20);
        var capacityRepository = sql.getMapper(StorageCapacityRepository.class);
        capacityRepository.initialize(exercise);
        var capacityStore = proxy(new StorageCapacityStore(capacityRepository, exercises, writes, identity, budget, clock, data), StorageCapacityStore.class);
        maintenance = proxy(new WorkspaceMaintenanceStore(sql.getMapper(WorkspaceMaintenanceRepository.class), exercises,
            sql.getMapper(DeletionRepository.class), identity, clock, budget, data, historyRepository), WorkspaceMaintenanceStore.class);
        var executionProblems = mock(ProblemRepository.class);
        String problemId = exercises.find(exercise).problemVersionId();
        when(executionProblems.find(problemId)).thenReturn(new ProblemVersionEntity(problemId, "fixture", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "{}"));
        when(executionProblems.executionAvailable(problemId)).thenReturn(true);
        var streams = sql.getMapper(TaskStreamRepository.class);
        var publisher = proxy(new TaskStreamPublisher(streams, json, clock), TaskStreamPublisher.class);
        childExecutions = proxy(new TestTaskStore(identity, secrets, exercises, writes, sql.getMapper(TestTaskRepository.class), executionProblems, clock, json,
            streams, publisher, sql.getMapper(FinalizationRepository.class), budget, maintenance, capacityStore), TestTaskStore.class);
        childLifecycle = proxy(new CopilotTestLifecycle(sql.getMapper(CopilotTestRepository.class), childExecutions), CopilotTestLifecycle.class);
        childCommands = proxy(new CommandTaskStore(identity, secrets, exercises, writes, sql.getMapper(TestTaskRepository.class),
            sql.getMapper(CommandTaskRepository.class), executionProblems, capacityStore, json, clock, data, history), CommandTaskStore.class);
        commandLifecycle = proxy(new CopilotCommandLifecycle(sql.getMapper(CopilotCommandRepository.class), childCommands, clock), CopilotCommandLifecycle.class);
        var lifecycle = proxy(new ExerciseTransactionService(exercises, identity, mock(ProblemTransactionService.class), clock, secrets, coordination,
            new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), capacityStore, selection), ExerciseTransactionService.class);
        var operations = proxy(new WorkspaceAiOperationService(identity, exercises, writes, capacityStore, clock), WorkspaceAiOperationService.class);
        conversations = proxy(new CopilotConversationStore(sql.getMapper(CopilotConversationRepository.class), identity,
            lifecycle, coordination, operations, clock), CopilotConversationStore.class);
        usage = proxy(new CopilotUsageService(sql.getMapper(CopilotUsageRepository.class), sql.getMapper(CopilotCompactionRepository.class), identity, lifecycle, conversations), CopilotUsageService.class);
        repository = sql.getMapper(CopilotTaskRepository.class);
        var skillLimits = new SkillProperties(50, 262144, 65536, 5242880, 32, 256, 4);
        personalSkills = new SkillService(new SkillPackageParser(skillLimits), proxy(new SkillStore(sql.getMapper(SkillRepository.class), identity, skillLimits, json, clock), SkillStore.class), json);
        skillSnapshots = proxy(new SkillSnapshots(sql.getMapper(SkillSnapshotRepository.class)), SkillSnapshots.class);
        skillContext = new CopilotSkillContext(skillSnapshots, repository, json, limits);
        files = mock(ExerciseService.class);
        references = new CopilotReferences(files, mock(TestTaskService.class), mock(CommandTaskService.class), coordination, secrets, json);
        var delegationLifecycle = proxy(new CopilotDelegationLifecycle(sql.getMapper(CopilotDelegationRepository.class), repository, clock), CopilotDelegationLifecycle.class);
        tasks = proxy(new CopilotTaskStore(repository, identity, lifecycle, coordination, selection, operations, clock, sql.getMapper(CopilotProposalRepository.class), childLifecycle, sql.getMapper(CopilotSteerRepository.class), sql.getMapper(CopilotCompactionRepository.class), skillSnapshots, conversations, commandLifecycle, references, delegationLifecycle), CopilotTaskStore.class);
        var contextRepository = sql.getMapper(CopilotCompactionRepository.class);
        compactions = proxy(new CopilotCompactionStore(contextRepository, repository, identity, secrets, lifecycle, coordination, operations, clock, conversations), CopilotCompactionStore.class);
        var callEvidence = new CopilotCallEvidence(new ModelUsageAccounting(new ModelUsageNormalizer(), new ModelPriceCatalog(), new ModelCostCalculator()));
        summaryExecution = proxy(new CopilotCompactionExecution(contextRepository, repository, sql.getMapper(CopilotSteerRepository.class), identity, coordination, operations, selection, limits, clock, callEvidence), CopilotCompactionExecution.class);
        var contextLimits = new CopilotContextProperties(80, 4, 8192, 4096, 8192, 512, 2048);
        execution = proxy(new CopilotExecutionStore(repository, identity, coordination, selection, operations, limits, clock, contextRepository,
            sql.getMapper(CopilotProposalRepository.class), secrets, childLifecycle, sql.getMapper(CopilotSteerRepository.class), callEvidence, skillSnapshots, commandLifecycle, delegationLifecycle), CopilotExecutionStore.class);
        var requestFactory = new CopilotRequestFactory();
        var planner = new CopilotContextPlanner(requestFactory, contextLimits, json, limits);
        contextPreparation = proxy(new CopilotContextPreparation(contextRepository, sql.getMapper(CopilotSteerRepository.class),
            planner, callEvidence, contextLimits, limits, skillContext, mock(CopilotProjectInstructions.class), repository), CopilotContextPreparation.class);
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 1, 0);
        sandbox = mock(SandboxWorkspaceGateway.class);
        when(sandbox.measureStorage(any())).thenAnswer(call -> measurement);
        capacity = new StorageCapacityService(capacityStore, sandbox, mock(WorkspaceMaintenanceCoordinator.class));
        var followupRepository = sql.getMapper(CopilotFollowupRepository.class);
        followups = proxy(new CopilotFollowupStore(followupRepository, repository, tasks, conversations, identity, coordination, operations, clock), CopilotFollowupStore.class);
        followupService = new CopilotFollowupService(followups, capacity, followupRepository, clock);
        btwStore = proxy(new CopilotBtwStore(sql.getMapper(CopilotBtwRepository.class), sql.getMapper(CopilotConversationRepository.class), conversations,
            repository, tasks, identity, secrets, operations, clock), CopilotBtwStore.class);
        btw = new CopilotBtwService(btwStore, capacity, json);
        delegations = new CopilotDelegationService(proxy(new CopilotDelegationStore(sql.getMapper(CopilotDelegationRepository.class), repository,
            sql.getMapper(CopilotConversationRepository.class), tasks, execution, delegationLifecycle, identity, coordination, operations, json, clock), CopilotDelegationStore.class), capacity, json);
        approvalModes = new CopilotApprovalModeService(proxy(new CopilotApprovalModeStore(sql.getMapper(CopilotApprovalModeRepository.class),
            identity, secrets, lifecycle, coordination, operations, clock), CopilotApprovalModeStore.class), capacity);
        when(files.find(SESSION, exercise)).thenReturn(new ExerciseResponse(exercise, "problem", instance, preparation, "ACTIVE", "1", "1", List.of("src/Main.java"), clock.instant(), clock.instant().plusSeconds(1800), "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null));
        when(files.read(SESSION, exercise, "src/Main.java")).thenReturn(new WorkspaceFileResponse(instance, "1", "src/Main.java", "class Main {}"));
        driver = mock(ModelCallDriver.class);
        // Keep non-streaming scenarios focused on completed responses; streaming cases replace this bridge explicitly.
        when(driver.call(any(), any(), any(), anyInt(), any(), any(), any())).thenAnswer(call -> driver.call(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5)));
        when(driver.call(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> driver.call(
            call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
        summaryRunner = new CopilotCompactionRunner(summaryExecution, contextPreparation, planner, new CopilotSummaryCodec(json),
            callEvidence, requestFactory, capacity, driver, limits, calls, json);
        contexts = new CopilotContextService(execution, contextPreparation, contextRepository, compactions, summaryRunner, capacity);
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
        commandStore = proxy(new CopilotCommandStore(sql.getMapper(CopilotCommandRepository.class), repository,
            sql.getMapper(CopilotApprovalModeRepository.class), execution, tasks, childCommands, commandLifecycle,
            identity, secrets, lifecycle, coordination, json, clock), CopilotCommandStore.class);
        commandTools = new CopilotCommandService(commandStore, sql.getMapper(CopilotCommandRepository.class), childCommands,
            mock(CommandTaskCoordinator.class), capacity, new CopilotCommandResultFormatter(), json);
        evidenceStore = proxy(new CopilotEvidenceStore(repository, contextRepository, identity, coordination, clock, skillSnapshots, sql.getMapper(CopilotDelegationRepository.class)), CopilotEvidenceStore.class);
        var evidenceTools = new CopilotEvidenceTools(evidenceStore, json);
        webTools = mock(CopilotWebTools.class);
        executor = new CopilotToolExecutor(new CopilotReadTools(files, json), new CopilotProposalPreparation(files, codec, json), proposals, json, testTools, evidenceTools, new CopilotSkillTools(evidenceStore, json), commandTools, webTools, new CopilotReferenceService(references, conversations, json), delegations);
        runner = new CopilotRunner(execution, capacity, driver, executor, limits, calls, contexts);
    }

    @Test
    void skillReadsReauthorizeAfterAdmissionEvenWhileTheWorkerLeaseIsStillValid() throws Exception {
        var original = personalSkills.save(SESSION, null, "private-skill", null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md",
            "---\nname: private-guide\ndescription: Guidance\n---\nPrivate retained content"))));
        personalSkills.change(SESSION, original.id(), "private-enable", original.version(), true, false);
        var task = tasks.accept(SESSION, exercise, CONTROL, "read-authority", "Inspect", true, measurement);
        String worker = UUID.randomUUID().toString(); execution.claim(task.id(), worker);
        var tool = new CopilotSkillTools(evidenceStore, json);
        String input = "{\"skill_id\":\"" + original.id() + "\"}";
        assertThat(tool.execute(task.id(), worker, input)).contains("Private retained content");
        when(identity.authorizePrivateDelivery(SESSION)).thenThrow(new BusinessException(IdentityErrorCode.UNAUTHENTICATED));
        assertThat(execution.current(task.id(), worker)).isNotNull();
        assertThat(tool.execute(task.id(), worker, input)).contains("UNAUTHENTICATED").doesNotContain("Private retained content");
        doReturn(identity.authorization(SESSION)).when(identity).authorizePrivateDelivery(SESSION);
        jdbc.update("UPDATE workspace_exercise SET deadline_at=? WHERE id=?::uuid", Timestamp.from(clock.instant().plusSeconds(1)), exercise);
        clock.now = clock.now.plusSeconds(2);
        assertThat(execution.current(task.id(), worker)).isNotNull();
        assertThat(tool.execute(task.id(), worker, input)).contains("COPILOT_INVALID_STATE").doesNotContain("Private retained content");
    }

    @Test
    void skillSnapshotCapacityFailureRollsBackTheWholeTaskWithoutDeletingPersonalOriginals() {
        String main = "---\nname: large-guidance\ndescription: Large guidance\n---\nBody";
        var original = personalSkills.save(SESSION, null, "large-skill", null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", main),
            new SkillFileDTO("references/data.txt", "x".repeat(262144 - main.length())))));
        var enabled = personalSkills.change(SESSION, original.id(), "large-enable", original.version(), true, false);
        var constrained = new StorageMeasurement(exercise, "A".repeat(26), 1, 268435456 - 300000);
        assertThatThrownBy(() -> tasks.accept(SESSION, exercise, CONTROL, "capacity-skill", "Inspect", true, constrained)).hasMessageContaining("STORAGE_BUDGET_EXCEEDED");
        assertThat(tasks.latest(SESSION, exercise)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_skill_snapshot", Integer.class)).isZero();
        assertThat(personalSkills.list(SESSION)).hasSize(1);
        personalSkills.change(SESSION, original.id(), "large-disable", enabled.version(), false, false);
        assertThat(tasks.accept(SESSION, exercise, CONTROL, "capacity-skill", "Inspect", true, constrained).state()).isEqualTo("QUEUED");
    }

    @Test
    void taskAdmissionAndPersonalEditsSerializeToOneWholeSkillVersion() throws Exception {
        String main = "---\nname: racing\ndescription: Guidance\n---\nOld body";
        var created = personalSkills.save(SESSION, null, "skill-create", null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", main))));
        assertThatThrownBy(() -> tasks.acceptWithSkills(SESSION, exercise, CONTROL, "disabled-task", "Inspect", true, List.of(created.id()), measurement)).hasMessageContaining("SKILL_NOT_FOUND");
        assertThat(tasks.latest(SESSION, exercise)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_skill_snapshot", Integer.class)).isZero();
        var enabled = personalSkills.change(SESSION, created.id(), "skill-enable", created.version(), true, false);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var edit = pool.submit(() -> {
                barrier.await();
                return personalSkills.save(SESSION, created.id(), "skill-update", enabled.version(),
                    new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", main.replace("Old body", "New body")))));
            });
            var start = pool.submit(() -> { barrier.await(); return tasks.acceptWithSkills(SESSION, exercise, CONTROL, "race-task", "Inspect", true, List.of(created.id()), measurement); });
            var updated = edit.get(10, TimeUnit.SECONDS); var task = start.get(10, TimeUnit.SECONDS);
            String owner = repository.find(task.id()).userId();
            var snapshot = skillSnapshots.list(owner, task.id()).getFirst();
            assertThat(snapshot.versionId()).isIn(created.contentVersion(), updated.contentVersion());
            assertThat(skillSnapshots.source(owner, task.id(), created.id()).filesPayload()).contains(snapshot.versionId().equals(created.contentVersion()) ? "Old body" : "New body");
            personalSkills.change(SESSION, created.id(), "skill-disable", updated.version(), false, false);
            assertThat(tasks.acceptWithSkills(SESSION, exercise, CONTROL, "race-task", "Inspect", true, List.of(created.id()), measurement)).isEqualTo(task);
        }
        verify(driver, never()).call(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void explicitSkillSelectionUsesTheTaskVersionAndRecordsLoadingOnlyOnDispatch() throws Exception {
        String main = "---\nname: chosen\ndescription: Explicit guidance\n---\nMarker original-guidance.";
        var created = personalSkills.save(SESSION, null, "skill-create", null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", main))));
        var enabled = personalSkills.change(SESSION, created.id(), "skill-enable", created.version(), true, false);
        var task = tasks.acceptWithSkills(SESSION, exercise, CONTROL, "explicit-task", "Follow selected guidance", true, List.of(created.id()), measurement);
        var owner = repository.find(task.id()).userId();
        assertThat(skillSnapshots.list(owner, task.id())).singleElement().satisfies(item -> assertThat(item.explicitLoaded()).isFalse());
        assertThatThrownBy(() -> tasks.acceptWithSkills(SESSION, exercise, CONTROL, "explicit-task", "Follow selected guidance", true, List.of(), measurement))
            .hasMessageContaining("COPILOT_IDEMPOTENCY_CONFLICT");
        personalSkills.save(SESSION, created.id(), "skill-update", enabled.version(), new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", main.replace("original-guidance", "new-guidance")))));
        steers.accept(SESSION, task.id(), CONTROL, "skill-steer", new CopilotSteerRequest("Keep the original task guidance"));
        assertThat(new SkillSnapshots(sql.getMapper(SkillSnapshotRepository.class)).list(owner, task.id())).isEqualTo(skillSnapshots.list(owner, task.id()));
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            assertThat(ChatMessageSerializer.messagesToJson(request.messages())).contains("original-guidance").doesNotContain("new-guidance");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Selected guidance applied")).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(skillSnapshots.list(owner, task.id())).singleElement().satisfies(item -> assertThat(item.explicitLoaded()).isTrue());
    }

    @Test
    void taskLoadsOnlyItsPinnedSkillAfterThePersonalOriginalIsDeleted() throws Exception {
        String main = "---\nname: review-java\ndescription: Review Java changes\n---\nUse deterministic clocks, marker pinned-original.";
        var created = personalSkills.save(SESSION, null, "skill-create", null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", main))));
        var enabled = personalSkills.change(SESSION, created.id(), "skill-enable", created.version(), true, false);
        var task = tasks.accept(SESSION, exercise, CONTROL, "skill-task", "Use applicable guidance", true, measurement);
        var callsSeen = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            String history = ChatMessageSerializer.messagesToJson(request.messages());
            if (callsSeen.incrementAndGet() == 1) {
                assertThat(history).contains("review-java", created.id()).doesNotContain("pinned-original");
                personalSkills.change(SESSION, created.id(), "skill-delete", enabled.version(), false, true);
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("load-guidance")
                    .name("load_skill").arguments("{\"skill_id\":\"" + created.id() + "\"}").build())).build(), 100);
            }
            assertThat(request.messages().getLast()).isInstanceOf(ToolExecutionResultMessage.class);
            assertThat(history).contains("pinned-original", created.contentVersion());
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Guidance applied")).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(callsSeen.get()).isEqualTo(2);
        assertThat(personalSkills.list(SESSION)).isEmpty();
        assertThat(tasks.messages(SESSION, exercise, -1)).anySatisfy(message -> assertThat(message.payload()).contains("load_skill", "pinned-original"));
    }

    @Test
    void usageReadsDoNotRecountCallsAndPublishedSummaryWaitsForNewReportedContext() throws Exception {
        retainedSummaryHistory();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder()
            .aiMessage(AiMessage.from("Actual usage")).tokenUsage(new TokenUsage(100, 20, 120)).build(), 100));
        var task = tasks.accept(SESSION, exercise, CONTROL, "usage-goal", "Continue", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        var before = usage.current(SESSION, exercise);
        assertThat(before.context().state()).isEqualTo("REPORTED");
        assertThat(before.context().inputTokens()).isEqualTo("100");
        assertThat(before.totals()).singleElement().satisfies(total -> {
            assertThat(total.calls()).isEqualTo("7"); assertThat(total.inputTokens()).isEqualTo("100");
            assertThat(total.totalTokens()).isEqualTo("120"); assertThat(total.pricedCalls()).isEqualTo("0");
        });
        assertThat(usage.current(SESSION, exercise)).isEqualTo(before);
        String worker = UUID.randomUUID().toString(); var job = compactions.accept(SESSION, exercise, CONTROL, "usage-summary", new CopilotCompactionRequest(true), measurement);
        summaryExecution.claim(job.id(), worker, null);
        var prepared = contextPreparation.prepare(summaryExecution.ticket(job.id(), worker));
        var candidate = prepared.plan().compaction();
        var dispatch = summaryExecution.begin(job.id(), worker, prepared, candidate, json.writeValueAsString(candidate.sourceIds()), measurement);
        summaryExecution.complete(job.id(), worker, dispatch.call().id(), summaryReceipt(dispatch));
        var after = usage.current(SESSION, exercise);
        assertThat(after.context().state()).isEqualTo("WAITING_UPDATE");
        assertThat(after.context().inputTokens()).isNull();
        assertThat(after.totals()).filteredOn(total -> "SUMMARY".equals(total.purpose())).singleElement()
            .satisfies(total -> assertThat(total.totalTokens()).isEqualTo("20"));
        assertThat(after.costs()).isEmpty();
    }

    @Test
    void automaticSummaryUsesTheCurrentlySelectedExactModelAndContinuesWithoutBrowserCredentials() throws Exception {
        retainedSummaryHistory();
        var selected = selection.resolveForCall(SESSION, exercise);
        var small = models.create(SESSION, selected.connection().id(), "small-context", new ModelConfigurationRequest("Small", "small", 60000, 4096,
            ModelThinkingMode.OFF, null, null, null, null));
        selection.select(SESSION, exercise, CONTROL, "select-small", new ModelInvocationReferenceDTO(selected.connection().id(), small.id(), 1, 1));
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ModelInvocationDTO target = call.getArgument(0); assertThat(target.model().id()).isEqualTo(small.id());
            ChatRequest request = call.getArgument(1); count.incrementAndGet();
            if (request.toolSpecifications().isEmpty()) {
                var job = sql.getMapper(CopilotCompactionRepository.class).pending(exercise);
                assertThat(job.triggerKind()).isEqualTo("AUTO");
                var dispatched = sql.getMapper(CopilotCompactionRepository.class).latestCall(job.id());
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(summaryReceipt(new CopilotCompactionDispatchDTO(dispatched, null, null)).summaryPayload())).build(), 100);
            }
            assertThat(ChatMessageSerializer.messagesToJson(request.messages())).contains("non-authoritative historical summary");
            if (count.get() == 2) return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id("current-model-read").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build())).build(), 100);
            assertThat(request.messages().getLast()).isInstanceOf(ToolExecutionResultMessage.class);
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Continued")).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "automatic-goal", "Continue", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(count.get()).isEqualTo(3);
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        verify(files, times(1)).read(SESSION, exercise, "src/Main.java");
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("1");
    }

    @Test
    void queuedManualSummaryRunsAtTheModelBoundaryAndContinuesTheOriginalGoal() throws Exception {
        retainedSummaryHistory();
        var task = tasks.accept(SESSION, exercise, CONTROL, "continue", "Continue this original goal", true, measurement);
        var job = compactions.accept(SESSION, exercise, CONTROL, "inline", new CopilotCompactionRequest(true), measurement);
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ChatRequest request = call.getArgument(1); count.incrementAndGet();
            if (request.toolSpecifications().isEmpty()) {
                var dispatched = sql.getMapper(CopilotCompactionRepository.class).latestCall(job.id());
                var receipt = summaryReceipt(new CopilotCompactionDispatchDTO(dispatched, null, null));
                steers.accept(SESSION, task.id(), CONTROL, "during-summary", new CopilotSteerRequest("Keep this new direction exact"));
                assertThat(ChatMessageSerializer.messagesToJson(request.messages())).doesNotContain("Keep this new direction exact");
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(receipt.summaryPayload())).build(), 100);
            }
            assertThat(ChatMessageSerializer.messagesToJson(request.messages())).contains("non-authoritative historical summary", "Continue this original goal", "Keep this new direction exact");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Continued original goal")).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(count.get()).isEqualTo(2);
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().state()).isEqualTo("APPLIED");
    }

    @Test
    void stoppingTheParentDuringSummaryPreventsPublicationAndTheNextChatDispatch() throws Exception {
        retainedSummaryHistory();
        var task = tasks.accept(SESSION, exercise, CONTROL, "stop-summary-parent", "Continue", true, measurement);
        var job = compactions.accept(SESSION, exercise, CONTROL, "stop-inline", new CopilotCompactionRequest(true), measurement);
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            count.incrementAndGet(); ChatRequest request = call.getArgument(1);
            assertThat(request.toolSpecifications()).isEmpty();
            var dispatched = sql.getMapper(CopilotCompactionRepository.class).latestCall(job.id());
            tasks.stop(SESSION, task.id(), "stop-parent");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(summaryReceipt(new CopilotCompactionDispatchDTO(dispatched, null, null)).summaryPayload())).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(count.get()).isEqualTo(1);
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("0");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_turn WHERE task_id=?::uuid", Integer.class, task.id())).isZero();
    }

    @Test
    void standaloneSummaryRepairsOnlyOneCompleteInvalidResponse() throws Exception {
        retainedSummaryHistory();
        var job = compactions.accept(SESSION, exercise, CONTROL, "invalid-summary", new CopilotCompactionRequest(true), measurement);
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            int ordinal = count.incrementAndGet();
            ChatRequest request = call.getArgument(1);
            if (ordinal == 2) assertThat(ChatMessageSerializer.messagesToJson(request.messages())).contains("previous completed summary was invalid");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("not valid JSON")).build(), 100);
        });
        summaryRunner.runStandalone(job.id());
        assertThat(count.get()).isEqualTo(2);
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("0");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_compaction_call", Integer.class)).isEqualTo(2);
    }

    @Test
    void boundedSourcePagesCanPublishMultipleProgressiveSummariesBeforeTheNextChatRequest() throws Exception {
        retainedSummaryHistory(35);
        var repository = sql.getMapper(CopilotCompactionRepository.class);
        var small = new CopilotContextProperties(80, 4, 8192, 4096, 8192, 32, 2048);
        var requests = new CopilotRequestFactory(); var evidence = new CopilotCallEvidence(new ModelUsageAccounting(new ModelUsageNormalizer(), new ModelPriceCatalog(), new ModelCostCalculator()));
        var planner = new CopilotContextPlanner(requests, small, json, limits);
        var preparation = proxy(new CopilotContextPreparation(repository, sql.getMapper(CopilotSteerRepository.class), planner, evidence, small, limits, skillContext, mock(CopilotProjectInstructions.class), this.repository), CopilotContextPreparation.class);
        var summaryWorker = new CopilotCompactionRunner(summaryExecution, preparation, planner, new CopilotSummaryCodec(json), evidence,
            requests, capacity, driver, limits, calls, json);
        var context = new CopilotContextService(execution, preparation, repository, compactions, summaryWorker, capacity);
        var summaries = new AtomicInteger(); var chats = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            if (request.toolSpecifications().isEmpty()) {
                summaries.incrementAndGet(); var job = repository.pending(exercise);
                var receipt = summaryReceipt(new CopilotCompactionDispatchDTO(repository.latestCall(job.id()), null, null));
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(receipt.summaryPayload())).build(), 100);
            }
            chats.incrementAndGet();
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("All older source pages compacted")).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "paged-history", "Continue the original goal", true, measurement);
        new CopilotRunner(execution, capacity, driver, executor, limits, calls, context).run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(summaries.get()).isEqualTo(2); assertThat(chats.get()).isEqualTo(1);
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("2");
    }

    @Test
    void aWaitingCompactionCannotApproveOrSplitTheOriginalPendingToolBatch() throws Exception {
        var workspace = new ProposalWorkspace(); var task = beginProposal(workspace, true);
        var proposal = proposals.list(SESSION, task.id(), 0).items().getFirst();
        var job = compactions.accept(SESSION, exercise, CONTROL, "wait-for-approval", new CopilotCompactionRequest(true), measurement);
        assertThat(summaryExecution.claim(job.id(), UUID.randomUUID().toString(), null)).isNull();
        assertThat(sql.getMapper(CopilotCompactionRepository.class).runnableStandalone()).doesNotContain(job.id());
        assertThat(compactions.cancel(SESSION, exercise, job.id()).state()).isEqualTo("CANCELLED");
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_APPROVAL");
        assertThat(proposals.summary(SESSION, proposal.id()).state()).isEqualTo("PENDING");
        assertThat(workspace.applies.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_compaction_call", Integer.class)).isZero();
    }

    @Test
    void summaryResponseCapacityIsReservedBeforeDispatchAndFailureKeepsTheOldView() throws Exception {
        retainedSummaryHistory();
        var job = compactions.accept(SESSION, exercise, CONTROL, "full-summary", new CopilotCompactionRequest(true), measurement);
        measurement = new StorageMeasurement(exercise, "A".repeat(26), 2, 268435456);
        summaryRunner.runStandalone(job.id());
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("0");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().reason()).isEqualTo("STORAGE_BUDGET_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_compaction_call", Integer.class)).isZero();
    }

    @Test
    void emptyHistoryDoesNotDispatchAPaidSummary() throws Exception {
        var job = compactions.accept(SESSION, exercise, CONTROL, "empty-summary", new CopilotCompactionRequest(true), measurement);
        summaryRunner.runStandalone(job.id());
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().state()).isEqualTo("NOT_NEEDED");
        var interrupted = compactions.accept(SESSION, exercise, CONTROL, "before-dispatch", new CopilotCompactionRequest(true), measurement);
        String worker = UUID.randomUUID().toString(); summaryExecution.claim(interrupted.id(), worker, null);
        summaryExecution.fail(interrupted.id(), worker, "MODEL_RESULT_UNKNOWN");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().reason()).isEqualTo("SERVICE_INTERRUPTED");
        verify(driver, never()).call(any(), any(), any(), anyInt(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_compaction_call", Integer.class)).isZero();
    }

    @Test
    void summaryPublicationPreservesOriginalMessagesAndSeparatesEstimatesFromReportedUsage() throws Exception {
        String worker = UUID.randomUUID().toString();
        var dispatch = dispatchedSummary(worker);
        int originalCount = jdbc.queryForObject("SELECT count(*) FROM copilot_message", Integer.class);
        assertThat(summaryExecution.complete(dispatch.call().compactionId(), worker, dispatch.call().id(), summaryReceipt(dispatch))).isFalse();
        var view = compactions.current(SESSION, exercise);
        assertThat(view.version()).isEqualTo("1"); assertThat(view.summary()).contains("historical");
        assertThat(view.requests().getFirst().state()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_message", Integer.class)).isEqualTo(originalCount);
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM copilot_compaction_call", Long.class)).isEqualTo(12L);
        assertThat(jdbc.queryForObject("SELECT estimated_input_tokens FROM copilot_compaction_call", Long.class)).isGreaterThan(12L);
    }

    @Test
    void summaryResultAfterAuthorizationEndsRetainsUsageWithoutPublishing() throws Exception {
        String worker = UUID.randomUUID().toString(); var dispatch = dispatchedSummary(worker);
        when(identity.tryAuthorizeMutation(SESSION)).thenReturn(null);
        summaryExecution.recover(dispatch.call().compactionId());
        summaryExecution.complete(dispatch.call().compactionId(), worker, dispatch.call().id(), summaryReceipt(dispatch));
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("0");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().state()).isEqualTo("CANCELLED");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().reason()).isEqualTo("AUTHORIZATION_ENDED");
        assertThat(jdbc.queryForObject("SELECT total_tokens FROM copilot_compaction_call", Long.class)).isEqualTo(20L);
    }

    @Test
    void cancellingADispatchedSummaryKeepsItsUsageButDoesNotPublishOrDeleteOriginalHistory() throws Exception {
        String worker = UUID.randomUUID().toString(); var dispatch = dispatchedSummary(worker);
        int originals = jdbc.queryForObject("SELECT count(*) FROM copilot_message", Integer.class);
        compactions.cancel(SESSION, exercise, dispatch.call().compactionId());
        assertThat(data.quiescent(exercise)).isFalse();
        summaryExecution.complete(dispatch.call().compactionId(), worker, dispatch.call().id(), summaryReceipt(dispatch));
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("0");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_message", Integer.class)).isEqualTo(originals);
        assertThat(jdbc.queryForObject("SELECT total_tokens FROM copilot_compaction_call", Long.class)).isEqualTo(20L);
        assertThat(data.quiescent(exercise)).isTrue();
    }

    @Test
    void compactionEvidenceIsRetainedAndDeletedOnlyAfterItsPaidRequestIsQuiescent() throws Exception {
        String worker = UUID.randomUUID().toString(); var dispatch = dispatchedSummary(worker);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise))).isInstanceOf(IllegalStateException.class);
        summaryExecution.complete(dispatch.call().compactionId(), worker, dispatch.call().id(), summaryReceipt(dispatch));
        assertThat(data.retainedBytes(exercise)).isPositive();
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(data.retainedBytes(exercise)).isZero();
        assertThat(sql.getMapper(ModelConfigurationRepository.class).find(alternateModelId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_context", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_compaction_call", Integer.class)).isZero();
    }

    @Test
    void expiredSummaryDispatchIsUnknownAndLateWorkerCannotPublishOrClaimAgain() throws Exception {
        String worker = UUID.randomUUID().toString(); var dispatch = dispatchedSummary(worker);
        clock.now = clock.now.plusSeconds(131);
        assertThat(summaryExecution.complete(dispatch.call().compactionId(), worker, dispatch.call().id(), summaryReceipt(dispatch))).isFalse();
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("0");
        assertThat(summaryExecution.recover(dispatch.call().compactionId())).isEqualTo("summary:" + dispatch.call().compactionId());
        assertThat(summaryExecution.complete(dispatch.call().compactionId(), worker, dispatch.call().id(), summaryReceipt(dispatch))).isFalse();
        assertThat(summaryExecution.claim(dispatch.call().compactionId(), UUID.randomUUID().toString(), null)).isNull();
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("0");
        assertThat(compactions.current(SESSION, exercise).requests().getFirst().reason()).isEqualTo("MODEL_RESULT_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_compaction_call", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT usage_incomplete FROM copilot_compaction_call", Boolean.class)).isTrue();
    }

    private void retainedSummaryHistory() throws Exception { retainedSummaryHistory(6); }

    private void retainedSummaryHistory(int size) throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder()
            .aiMessage(AiMessage.from("Retained historical detail ".repeat(300))).build(), 100));
        for (int index = 0; index < size; index++) {
            var task = tasks.accept(SESSION, exercise, CONTROL, "history-" + index, "Explain " + index, true, measurement);
            runner.run(task.id(), UUID.randomUUID().toString());
            assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        }
    }

    private CopilotCompactionDispatchDTO dispatchedSummary(String worker) throws Exception {
        retainedSummaryHistory();
        var job = compactions.accept(SESSION, exercise, CONTROL, "summarize", new CopilotCompactionRequest(true), measurement);
        assertThat(summaryExecution.claim(job.id(), worker, null)).isNotNull();
        var prepared = contextPreparation.prepare(summaryExecution.ticket(job.id(), worker));
        var candidate = prepared.plan().compaction(); assertThat(candidate).isNotNull();
        return summaryExecution.begin(job.id(), worker, prepared, candidate, json.writeValueAsString(candidate.sourceIds()), measurement);
    }

    private CopilotCompactionReceiptDTO summaryReceipt(CopilotCompactionDispatchDTO dispatch) throws Exception {
        String source = json.readTree(dispatch.call().sourceIdsPayload()).get(0).asText();
        String summary = json.writeValueAsString(Map.of("goal", "Past work", "decisions", List.of(), "changes", List.of(),
            "state", "historical", "evidence", List.of(source)));
        return new CopilotCompactionReceiptDTO(ChatMessageSerializer.messageToJson(AiMessage.from(summary)), summary, null,
            12L, 8L, 20L, false, "{\"input\":12,\"output\":8,\"total\":20}", new ModelUsageNormalizer().normalize(new TokenUsage(12, 8, 20)));
    }

    @Test
    void manualCompactionAdmissionIsIdempotentAndExcludesNewGoalsUntilCancellation() {
        var input = new CopilotCompactionRequest(true);
        var job = compactions.accept(SESSION, exercise, CONTROL, "compact-1", input, measurement);
        assertThat(job.state()).isEqualTo("WAITING"); assertThat(Long.parseLong(job.sequence())).isPositive();
        assertThat(compactions.accept(SESSION, exercise, "stale-control", "compact-1", input, measurement).id()).isEqualTo(job.id());
        assertThat(compactions.current(SESSION, exercise).requests()).hasSize(1);
        assertThatThrownBy(() -> tasks.accept(SESSION, exercise, CONTROL, "blocked", "Explain", true, measurement))
            .isInstanceOf(BusinessException.class).hasMessage("COPILOT_BUSY");
        assertThatThrownBy(() -> compactions.accept(SESSION, exercise, CONTROL, "compact-2", input, measurement))
            .isInstanceOf(BusinessException.class).hasMessage("COPILOT_COMPACTION_BUSY");
        assertThat(compactions.cancel(SESSION, exercise, job.id()).state()).isEqualTo("CANCELLED");
        assertThat(compactions.replay(SESSION, exercise, "compact-1", input).state()).isEqualTo("CANCELLED");
        assertThat(tasks.accept(SESSION, exercise, CONTROL, "after-cancel", "Explain", true, measurement).state()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_compaction_call", Integer.class)).isZero();
    }

    @Test
    void manualCompactionDoesNotReplaceAnActiveParentsEndedAuthorization() {
        tasks.accept(SESSION, exercise, CONTROL, "original", "Explain", true, measurement);
        when(identity.tryAuthorizeMutation(SESSION)).thenReturn(null);
        assertThatThrownBy(() -> compactions.accept(SESSION, exercise, CONTROL, "compact", new CopilotCompactionRequest(true), measurement))
            .isInstanceOf(BusinessException.class).hasMessage("COPILOT_INVALID_STATE");
        assertThat(compactions.current(SESSION, exercise).requests()).isEmpty();
    }

    @Test
    void readEvidenceUsesRetainedVisibleTextAndTheNormalPairedToolCheckpoint() throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder()
            .aiMessage(AiMessage.builder().text("Confirmed old answer").thinking("opaque-signature").build()).build(), 100));
        var first = tasks.accept(SESSION, exercise, CONTROL, "first", "Explain", true, measurement);
        runner.run(first.id(), UUID.randomUUID().toString());
        String source = tasks.messages(SESSION, exercise, -1).getLast().id();
        var request = ToolExecutionRequest.builder().id("evidence-1").name("read_evidence").arguments("{\"id\":\"" + source + "\"}").build();
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            if (count.incrementAndGet() == 1) return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(request)).build(), 100);
            ChatRequest sent = call.getArgument(1);
            var result = (ToolExecutionResultMessage) sent.messages().getLast();
            assertThat(result.id()).isEqualTo(request.id());
            assertThat(result.text()).contains("Confirmed old answer", source).doesNotContain("opaque-signature");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Used retained evidence")).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "second", "Read retained evidence", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(count.get()).isEqualTo(2); verify(files, never()).read(anyString(), anyString(), anyString());
    }

    @Test
    void evidenceReadRejectsForeignOrOrphanReferencesRetiredWorkersAndRevokedAuthorization() {
        var task = tasks.accept(SESSION, exercise, CONTROL, "evidence-scope", "Inspect", true, measurement);
        String worker = UUID.randomUUID().toString(); execution.claim(task.id(), worker);
        String own = tasks.messages(SESSION, exercise, -1).getFirst().id();
        assertThat(evidenceStore.read(task.id(), worker, own).id()).isEqualTo(own);
        assertThatThrownBy(() -> evidenceStore.read(task.id(), UUID.randomUUID().toString(), own)).isInstanceOf(BusinessException.class).hasMessage("COPILOT_INVALID_STATE");
        String foreign = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO copilot_message(id,task_id,exercise_id,kind,payload,created_at) VALUES(?::uuid,?::uuid,?::uuid,'USER',?,?)",
            foreign, UUID.randomUUID().toString(), UUID.randomUUID().toString(), ChatMessageSerializer.messageToJson(UserMessage.from("not-this-exercise")), Timestamp.from(clock.instant()));
        assertThatThrownBy(() -> evidenceStore.read(task.id(), worker, foreign)).isInstanceOf(BusinessException.class).hasMessage("COPILOT_EVIDENCE_NOT_FOUND");
        when(identity.authorizePrivateDelivery(SESSION)).thenThrow(new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> evidenceStore.read(task.id(), worker, own)).isInstanceOf(IdentityFailure.class);
    }

    @Test
    void contextPreparationIsOutsideTransactionsAndStaleDirectionsOrModelsCannotDispatch() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "context-start", "Inspect", true, measurement);
        String worker = UUID.randomUUID().toString(); assertThat(execution.claim(task.id(), worker)).isNotNull();
        var ticket = execution.contextTicket(task.id(), worker);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            assertThatThrownBy(() -> contextPreparation.prepare(ticket)).isInstanceOf(IllegalTransactionStateException.class));
        var original = contextPreparation.prepare(ticket);
        var adjustment = steers.accept(SESSION, task.id(), CONTROL, "during-preparation", new CopilotSteerRequest("Keep original decisions"));
        assertThat(execution.beginModel(task.id(), worker, original, measurement)).isNull();
        var beforeSwitch = contexts.beforeModel(task.id(), worker); switchSelectedModel.run();
        assertThat(execution.beginModel(task.id(), worker, beforeSwitch, measurement)).isNull();
        assertThat(repository.latestTurn(task.id())).isNull();
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("RECEIVED");
        var current = contexts.beforeModel(task.id(), worker);
        var dispatched = execution.beginModel(task.id(), worker, current, measurement);
        assertThat(dispatched.target().model().id()).isEqualTo(alternateModelId);
        assertThat(dispatched.request().messages().toString()).contains("Keep original decisions");
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().id()).isEqualTo(adjustment.id());
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT estimated_input_tokens FROM copilot_turn WHERE id=?::uuid", Long.class, dispatched.turn().id())).isPositive();
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM copilot_turn WHERE id=?::uuid", Long.class, dispatched.turn().id())).isNull();
        clock.now = clock.now.plusSeconds(130); execution.recover(task.id());
    }

    @Test
    void projectInstructionSnapshotPublishesOnlyWithMatchingObservedRevisionAndRemainsPrivate() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "project-start", "Inspect", true, measurement);
        String worker = UUID.randomUUID().toString(); execution.claim(task.id(), worker);
        var prepared = contexts.beforeModel(task.id(), worker);
        String payload = "{\"exerciseId\":\"" + exercise + "\",\"revision\":\"1\",\"sources\":[],\"scopeTruncated\":false}";
        var stale = new CopilotContextPreparedDTO(prepared.ticket(), prepared.input(), prepared.plan(), prepared.adjustments(), prepared.configurationPayload(), "2", payload);
        assertThat(execution.beginModel(task.id(), worker, stale, measurement)).isNull();
        assertThat(repository.latestTurn(task.id())).isNull();
        var current = new CopilotContextPreparedDTO(prepared.ticket(), prepared.input(), prepared.plan(), prepared.adjustments(), prepared.configurationPayload(), "1", payload);
        var dispatched = execution.beginModel(task.id(), worker, current, measurement);
        assertThat(dispatched).isNotNull();
        assertThat(tasks.projectInstructions(SESSION, task.id())).isEqualTo(payload);
        // Tied timestamps and reverse UUID order must not drop the most recently executed path.
        for (int position = 0; position < 33; position++) {
            jdbc.update("INSERT INTO copilot_tool(id,turn_id,task_id,exercise_id,position,call_id,name,arguments,state,created_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'view_file',?,'SUCCEEDED',?)",
                String.format("00000000-0000-0000-0000-%012d", 33 - position), dispatched.turn().id(), task.id(), exercise,
                position, "read-" + position, "{\"path\":\"src/" + position + "/Main.java\"}", Timestamp.from(clock.instant()));
        }
        assertThat(repository.instructionPaths(task.id())).hasSize(32).first().isEqualTo("{\"path\":\"src/32/Main.java\"}");
        assertThat(repository.instructionPaths(task.id())).doesNotContain("{\"path\":\"src/0/Main.java\"}");
        when(identity.authorizePrivateDelivery(SESSION)).thenThrow(new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> tasks.projectInstructions(SESSION, task.id())).isInstanceOf(IdentityFailure.class);
    }

    @Test
    void deletingModelAfterContextPreparationDoesNotDispatchItsCachedCredential() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "context-delete", "Inspect", true, measurement);
        String worker = UUID.randomUUID().toString(); execution.claim(task.id(), worker);
        var prepared = contexts.beforeModel(task.id(), worker); deleteSelectedModel.run();
        assertThatThrownBy(() -> execution.beginModel(task.id(), worker, prepared, measurement)).isInstanceOf(BusinessException.class)
            .hasMessage("MODEL_CONFIGURATION_NOT_FOUND");
        assertThat(repository.latestTurn(task.id())).isNull();
        verify(driver, never()).call(any(), any(), any(), anyInt(), any(), any());
        tasks.stop(SESSION, task.id(), "stop"); execution.finish(task.id(), worker, "STOPPED", "USER_STOP");
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
        var rebuilt = new CopilotRunner(execution, capacity, driver, executor, limits, calls, contexts);
        rebuilt.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(steers.list(SESSION, task.id(), 0).items()).extracting(value -> value.state()).containsExactly("APPLIED", "APPLIED");
        assertThat(steers.list(SESSION, task.id(), 0).items()).extracting(value -> value.id()).containsExactly(second.id(), first.id());
        assertThat(repository.latest(exercise).id()).isEqualTo(task.id());
    }

    @ParameterizedTest
    @CsvSource({"OPENAI_CHAT,false", "OPENAI_CHAT,true", "OPENAI_RESPONSES,true", "ANTHROPIC,true"})
    void steerInvalidatesOldApprovalAndPairsUnstartedToolsBeforeReplanning(ModelProtocol protocol, boolean thinking) throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true, thinking, protocol);
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
            if (thinking) assertThat(request.messages().stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast).toList())
                .singleElement().satisfies(message -> {
                    assertThat(message.thinking()).isEqualTo("opaque-fixture-thinking");
                    assertThat(message.attributes()).containsAllEntriesOf(protocolAttributes(protocol));
                });
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Explanation only")).build(), 100);
        });
        new CopilotRunner(execution, capacity, driver, executor, limits, calls, contexts).run(task.id(), UUID.randomUUID().toString());
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
    void modelSwitchQuotesSynthesizedOldResultsButKeepsTheNewModelsToolPairNative() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "switch-steer", "Original", true, measurement);
        var count = new AtomicInteger();
        var oldRead = ToolExecutionRequest.builder().id("never-started").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        var newRead = ToolExecutionRequest.builder().id("new-model-read").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            int ordinal = count.incrementAndGet();
            if (ordinal == 1) {
                switchSelectedModel.run();
                steers.accept(SESSION, task.id(), CONTROL, "switch-direction", new CopilotSteerRequest("New direction"));
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(oldRead)).build(), 100);
            }
            assertThat(ChatMessageSerializer.messagesToJson(request.messages())).contains("NOT_EXECUTED", "New direction");
            if (ordinal == 2) {
                assertThat(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)).isEmpty();
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(newRead)).build(), 100);
            }
            assertThat(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast))
                .singleElement().satisfies(result -> assertThat(result.id()).isEqualTo(newRead.id()));
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Done")).build(), 100);
        });
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(count.get()).isEqualTo(3); verify(files, times(1)).read(SESSION, exercise, "src/Main.java");
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
            new CopilotRunner(execution, capacity, actualDriver, executor, limits, calls, contexts).run(task.id(), UUID.randomUUID().toString());
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
            Consumer<String> thinking = call.getArgument(6);
            thinking.accept("Unconfirmed thinking");
            assertThat(tasks.delivery(SESSION, task.id()).partial().thinking()).isEqualTo("Unconfirmed thinking");
            assertThat(tasks.delivery(SESSION, task.id()).partial().text()).contains("Unconfirmed reply");
            assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("RUNNING");
            assertThat(tasks.messages(SESSION, exercise, -1)).hasSize(1);
            verify(files, never()).read(any(), any(), any());
            if (!completes) throw new BusinessException(ModelAccessErrorCode.MODEL_NETWORK_UNAVAILABLE);
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Confirmed reply")).tokenUsage(new TokenUsage(100, 20, 120)).build(), 100);
        }).when(driver).call(any(), any(), any(), anyInt(), any(), any(), any());
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.delivery(SESSION, task.id()).partial().thinking()).isEqualTo(completes ? "" : "Unconfirmed thinking");
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo(completes ? "SUCCEEDED" : "FAILED");
        assertThat(tasks.delivery(SESSION, task.id()).partial().text()).isEqualTo(completes ? "" : "Unconfirmed reply, not tool arguments");
        assertThat(tasks.messages(SESSION, exercise, -1)).hasSize(completes ? 2 : 1);
        var accounting = usage.current(SESSION, exercise);
        assertThat(accounting.totals()).singleElement().satisfies(total -> {
            assertThat(total.calls()).isEqualTo("1");
            assertThat(total.completeCalls()).isEqualTo("0");
            assertThat(total.pricedCalls()).isEqualTo("0");
            assertThat(total.inputTokens()).isEqualTo(completes ? "100" : null);
        });
        assertThat(accounting.costs()).isEmpty();
        assertThat(usage.current(SESSION, exercise)).isEqualTo(accounting);
        String usage = jdbc.queryForObject("SELECT usage_payload FROM copilot_turn WHERE task_id=?::uuid", String.class, task.id());
        if (completes) assertThat(json.readTree(usage).get("inputTokenCount").asInt()).isEqualTo(100);
        else assertThat(usage).isNull();
        String configuration = jdbc.queryForObject("SELECT configuration_payload FROM copilot_turn WHERE task_id=?::uuid", String.class, task.id());
        assertThat(configuration).contains("OPENAI_CHAT", "https://example.test").doesNotContain("fixture-not-real", "apiKey");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void btwHasIndependentLoopHistoryAndCannotExecuteWriteToolsOrUpgradeItsLane() throws Exception {
        var main = tasks.accept(SESSION, exercise, CONTROL, "btw-main", "Main goal", true, measurement);
        repository.message(new CopilotMessageEntity(0, UUID.randomUUID().toString(), main.id(), exercise, "ASSISTANT",
            ChatMessageSerializer.messageToJson(AiMessage.builder().text("Main saved answer").thinking("private-main-thinking").build()), clock.instant()));
        var input = new CopilotBtwRequest("Side question", true);
        var side = btw.start(SESSION, exercise, exercise, CONTROL, "btw-side", input);
        assertThat(btw.start(SESSION, exercise, exercise, CONTROL, "btw-side", input).id()).isEqualTo(side.id());
        assertThat(side.conversationId()).isNotEqualTo(exercise);
        assertThat(repository.find(main.id()).state()).isEqualTo("QUEUED");
        assertThat(conversations.list(SESSION, exercise)).extracting(item -> item.id()).containsExactly(exercise);
        assertCode(() -> tasks.acceptInConversation(SESSION, exercise, CONTROL, "upgrade", "write", true, List.of(), measurement, side.conversationId()), "COPILOT_INVALID_REQUEST");
        assertCode(() -> followups.accept(SESSION, exercise, side.conversationId(), CONTROL, "upgrade-queue", new CopilotFollowupRequest("write", true), measurement), "COPILOT_INVALID_REQUEST");
        var turns = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            assertThat(request.parameters().toolSpecifications()).extracting(tool -> tool.name()).doesNotContain("bash", "run_tests", "propose_changes").contains("view_file", "web_search");
            if (turns.getAndIncrement() == 0) {
                assertThat(ChatMessageSerializer.messagesToJson(request.messages())).contains("Main goal", "Main saved answer", "Side question", "Read-only main-conversation projection").doesNotContain("private-main-thinking");
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("forbidden").name("bash").arguments("{\"command\":\"touch bad\",\"directory\":\".\",\"expected_revision\":\"1\"}").build()))).build(), 100);
            }
            assertThat(ChatMessageSerializer.messagesToJson(request.messages())).contains("BTW_READ_ONLY");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Side conclusion")).build(), 100);
        });
        runner.run(side.id(), UUID.randomUUID().toString());
        assertThat(repository.find(side.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(repository.find(main.id()).state()).isEqualTo("QUEUED");
        assertThat(repository.context(exercise)).noneMatch(message -> message.payload().contains("Side question") || message.payload().contains("Side conclusion"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_command WHERE exercise_id=?::uuid", Integer.class, exercise)).isZero();
        assertThat(usage.currentInConversation(SESSION, exercise, side.conversationId()).totals()).anyMatch(total -> "2".equals(total.calls()));
    }

    @Test
    void delegationRunsARealChildAndHandsOffExactlyOnceBeforeParentContinues() throws Exception {
        var parent = tasks.accept(SESSION, exercise, CONTROL, "delegate-parent", "Parent goal", true, measurement);
        repository.message(new CopilotMessageEntity(0, UUID.randomUUID().toString(), parent.id(), exercise, "ASSISTANT",
            ChatMessageSerializer.messageToJson(AiMessage.builder().text("Unrelated main chat").thinking("private-parent-thinking").build()), clock.instant()));
        var parentTurns = new AtomicInteger(); var childTurns = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            boolean child = request.messages().stream().anyMatch(message -> message instanceof UserMessage user && user.singleText().startsWith("Child goal"));
            String payload = ChatMessageSerializer.messagesToJson(request.messages());
            if (child) {
                assertThat(request.parameters().toolSpecifications()).extracting(tool -> tool.name()).contains("bash", "propose_changes", "run_tests").doesNotContain("delegate_task");
                assertThat(payload).contains("Parent goal", "Relevant pointers").doesNotContain("Unrelated main chat", "private-parent-thinking");
                if (childTurns.getAndIncrement() == 0) return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                    ToolExecutionRequest.builder().id("forbidden-recursion").name("delegate_task").arguments("{\"goal\":\"Grandchild\"}").build()))).build(), 100);
                assertThat(payload).contains("DELEGATION_NOT_ALLOWED");
                return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.builder().text("Child verified outcome").thinking("private-child-thinking").build()).build(), 100);
            }
            if (parentTurns.getAndIncrement() == 0) return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("delegate-once").name("delegate_task").arguments("{\"goal\":\"Child goal\",\"context\":\"Relevant pointers\"}").build()))).build(), 100);
            assertThat(payload).contains("Child verified outcome", "childTaskId").doesNotContain("private-child-thinking");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Parent continued after handoff")).build(), 100);
        });
        runner.run(parent.id(), UUID.randomUUID().toString());
        var parked = repository.find(parent.id());
        assertThat(parked.state()).isEqualTo("WAITING_CHILD"); assertThat(parked.workerId()).isNull();
        assertThat(repository.active(exercise)).isTrue();
        var binding = sql.getMapper(CopilotDelegationRepository.class).pending(parent.id());
        var child = repository.find(binding.childTaskId());
        assertThat(child.lane()).isEqualTo("CHILD"); assertThat(child.userId()).isEqualTo(parked.userId());
        assertThat(child.sessionHash()).isEqualTo(parked.sessionHash());
        assertCode(() -> tasks.acceptInConversation(SESSION, exercise, CONTROL, "upgrade-child", "Escape", true, List.of(), measurement, child.conversationId()), "COPILOT_INVALID_REQUEST");
        assertThat(conversations.list(SESSION, exercise)).noneMatch(row -> child.conversationId().equals(row.id()));
        runner.run(parent.id(), UUID.randomUUID().toString());
        assertThat(parentTurns.get()).isEqualTo(1);
        runner.run(child.id(), UUID.randomUUID().toString());
        assertThat(repository.find(child.id()).state()).isEqualTo("SUCCEEDED");
        delegations.recover(parent.id()); delegations.recover(parent.id());
        assertThat(repository.find(parent.id()).state()).isEqualTo("QUEUED");
        assertThat(repository.context(exercise).stream().filter(message -> "TOOL".equals(message.kind()))).hasSize(1);
        String worker = UUID.randomUUID().toString(); assertThat(execution.claim(parent.id(), worker)).isNotNull();
        var source = sql.getMapper(CopilotDelegationRepository.class).lastMessage(child.id(), "ASSISTANT");
        assertThat(evidenceStore.read(parent.id(), worker, source.id()).payload()).contains("Child verified outcome");
        new TransactionTemplate(transactions).executeWithoutResult(status -> repository.park(parent.id(), worker, "QUEUED", null));
        runner.run(parent.id(), UUID.randomUUID().toString());
        assertThat(repository.find(parent.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(parentTurns.get()).isEqualTo(2); assertThat(childTurns.get()).isEqualTo(2);
        assertThat(sql.getMapper(CopilotDelegationRepository.class).count(parent.id())).isEqualTo(1);
        assertThat(delegations.list(SESSION, parent.id()).getFirst().resultPayload()).contains("SUCCEEDED").doesNotContain("private-child-thinking");
        assertThat(usage.currentInConversation(SESSION, exercise, exercise).totals()).anyMatch(total -> "4".equals(total.calls()));
    }

    @Test
    void parentStopWaitsForDelegatedBashCancellationProofAndDoesNotReplayIt() throws Exception {
        var parent = beginDelegation();
        var child = delegations.list(SESSION, parent.id()).getFirst().child();
        var request = ToolExecutionRequest.builder().id("child-bash").name("bash")
            .arguments("{\"expected_revision\":\"1\",\"command\":\"echo hello\",\"directory\":\".\"}").build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build(), 100));
        runner.run(child.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, child.id()).state()).isEqualTo("WAITING_APPROVAL");
        var command = commandTools.recent(SESSION, child.id(), 0).commands().getFirst();
        var approved = commandTools.decide(SESSION, command.id(), CONTROL, "approve-child-command", new CopilotProposalDecisionRequest("APPROVE", null));
        var executionChild = childCommands.find(approved.commandTaskId());
        assertThat(data.changeContext(exercise, executionChild.id()).conversationId()).isEqualTo(exercise);
        assertThat(data.changeContext(exercise, executionChild.id()).taskId()).isEqualTo(child.id());
        var coordinator = new CommandTaskCoordinator(childCommands, sandbox);
        when(sandbox.inventory(anyString(), anyString())).thenReturn(ExecutionInventory.newBuilder().setHostEpoch("A".repeat(26)).build());
        when(sandbox.startCommand(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        assertThatThrownBy(() -> coordinator.observe(executionChild)).isInstanceOf(StatusRuntimeException.class);
        tasks.stop(SESSION, parent.id(), "stop-delegating-parent");
        commandTools.recoverTask(child.id()); execution.recover(child.id());
        when(sandbox.stopCommand(any())).thenReturn(StopTaskResponse.getDefaultInstance());
        assertThatThrownBy(() -> coordinator.observe(childCommands.find(executionChild.id()))).isInstanceOf(IllegalStateException.class);
        delegations.recover(parent.id()); execution.recover(parent.id());
        assertThat(tasks.find(SESSION, parent.id()).state()).isEqualTo("STOPPING");
        assertThat(data.quiescent(exercise)).isFalse();
        assertThat(data.protectedCommandRecords(exercise)).contains(executionChild.id());
        when(sandbox.stopCommand(any())).thenReturn(StopTaskResponse.newBuilder().setHostEpoch("A".repeat(26)).setCancelledBeforeRegistration(true).build());
        coordinator.observe(childCommands.find(executionChild.id()));
        commandTools.recoverTask(child.id()); execution.recover(child.id());
        delegations.recover(parent.id()); execution.recover(parent.id()); delegations.recover(parent.id());
        assertThat(tasks.find(SESSION, child.id()).state()).isEqualTo("STOPPED");
        assertThat(tasks.find(SESSION, parent.id()).state()).isEqualTo("STOPPED");
        assertThat(delegations.list(SESSION, parent.id()).getFirst().resultPayload()).contains("STOPPED");
        assertThat(data.quiescent(exercise)).isTrue();
        verify(sandbox, times(1)).startCommand(any()); verify(driver, times(2)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void delegationRecoversUnknownChildWithoutNewDispatchAndExpiryErasesItsRecords() throws Exception {
        var parent = beginDelegation();
        var child = delegations.list(SESSION, parent.id()).getFirst().child();
        String worker = UUID.randomUUID().toString(); assertThat(execution.claim(child.id(), worker)).isNotNull();
        assertThat(execution.beginModel(child.id(), worker, contexts.beforeModel(child.id(), worker), measurement)).isNotNull();
        clock.now = clock.instant().plusSeconds(130); execution.recover(child.id());
        delegations.recover(parent.id()); delegations.recover(parent.id());
        assertThat(tasks.find(SESSION, child.id()).reason()).isEqualTo("MODEL_RESULT_UNKNOWN");
        assertThat(delegations.list(SESSION, parent.id()).getFirst().resultPayload()).contains("MODEL_RESULT_UNKNOWN", "INTERRUPTED");
        assertThat(tasks.find(SESSION, parent.id()).state()).isEqualTo("QUEUED");
        runner.run(child.id(), UUID.randomUUID().toString());
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
        clock.now = clock.instant().plusSeconds(1801); execution.recover(parent.id());
        assertThat(data.quiescent(exercise)).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(sql.getMapper(CopilotDelegationRepository.class).count(parent.id())).isZero();
        assertThat(repository.find(child.id())).isNull();
    }

    @Test
    void delegatedAutoProposalKeepsTheSharedRevisionFence() throws Exception {
        var parent = beginDelegation(); var child = delegations.list(SESSION, parent.id()).getFirst().child();
        approvalModes.change(SESSION, exercise, CONTROL, "delegated-auto", new CopilotApprovalModeRequest("AUTO", "0"));
        var workspace = new ProposalWorkspace();
        var proposal = workspace.proposal();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(proposal))).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Revision conflict; no child edit was applied.")).build(), 100));
        runner.run(child.id(), UUID.randomUUID().toString());
        var pending = proposals.list(SESSION, child.id(), 0).items().getFirst();
        assertThat(pending.mode()).isEqualTo("AUTO");
        sql.getMapper(WorkspaceWriteRepository.class).observed(exercise, 2, "[\"" + ProposalWorkspace.PATH + "\"]");
        automatic.apply(child.id());
        var rejected = proposals.summary(SESSION, pending.id());
        assertThat(rejected.state()).isEqualTo("INVALIDATED"); assertThat(rejected.failureCode()).isEqualTo("REVISION_CONFLICT");
        assertThat(workspace.applies.get()).isZero();
        runner.run(child.id(), UUID.randomUUID().toString()); delegations.recover(parent.id());
        assertThat(delegations.list(SESSION, parent.id()).getFirst().resultPayload()).contains("Revision conflict", "observedRevision\":\"2");
        assertThat(tasks.find(SESSION, parent.id()).state()).isEqualTo("QUEUED");
    }

    @Test
    void revokedParentAuthorityStopsItsQueuedChildWithoutCallingTheModel() throws Exception {
        var parent = beginDelegation(); var child = delegations.list(SESSION, parent.id()).getFirst().child();
        invalidateOriginalAuthorizationUsingRealProxy();
        delegations.recover(parent.id()); execution.recover(parent.id());
        assertThat(repository.find(child.id()).state()).isEqualTo("STOPPED");
        assertThat(repository.find(parent.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(repository.find(parent.id()).reason()).isEqualTo("AUTHORIZATION_ENDED");
        assertThat(sql.getMapper(CopilotDelegationRepository.class).list(parent.id()).getFirst().state()).isEqualTo("RESOLVED");
        assertThatThrownBy(() -> delegations.list(SESSION, parent.id())).hasMessage("UNAUTHENTICATED");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void delegationHasAHardPerParentBoundEvenWhenEveryChildIsExplicitlyStopped() throws Exception {
        var parent = beginDelegation();
        for (int index = 0; index < 8; index++) {
            if (index > 0) runner.run(parent.id(), UUID.randomUUID().toString());
            var binding = sql.getMapper(CopilotDelegationRepository.class).pending(parent.id());
            assertThat(binding).isNotNull();
            tasks.stop(SESSION, binding.childTaskId(), "stop-bounded-child-" + index);
            delegations.recover(parent.id());
        }
        var request = ToolExecutionRequest.builder().id("ninth-child").name("delegate_task").arguments("{\"goal\":\"Child goal\"}").build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Delegation limit understood.")).build(), 100));
        runner.run(parent.id(), UUID.randomUUID().toString());
        assertThat(sql.getMapper(CopilotDelegationRepository.class).count(parent.id())).isEqualTo(8);
        assertThat(delegations.list(SESSION, parent.id())).hasSize(8).allSatisfy(row -> assertThat(row.child().state()).isEqualTo("STOPPED"));
        assertThat(repository.context(exercise)).anyMatch(message -> message.payload().contains("COPILOT_DELEGATION_LIMIT"));
        assertThat(repository.find(parent.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(repository.activeLane(exercise, "CHILD")).isFalse();
    }

    @Test
    void exerciseExpiryStopsBothDelegationLanesWithoutStartingTheQueuedChild() throws Exception {
        var parent = beginDelegation(); var child = delegations.list(SESSION, parent.id()).getFirst().child();
        clock.now = clock.instant().plusSeconds(1801);
        delegations.recover(parent.id()); execution.recover(parent.id());
        assertThat(repository.find(child.id()).state()).isEqualTo("STOPPED");
        assertThat(repository.find(parent.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(repository.find(parent.id()).reason()).isEqualTo("EXERCISE_ENDED");
        assertThat(data.quiescent(exercise)).isTrue();
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void delegatedEvidenceIsExplicitAndCannotReadAnUnrelatedSideConversation() throws Exception {
        var parent = beginDelegation(); var child = delegations.list(SESSION, parent.id()).getFirst().child();
        var side = btw.start(SESSION, exercise, exercise, CONTROL, "private-side", new CopilotBtwRequest("Private side history", true));
        var privateMessage = repository.context(side.conversationId()).getFirst();
        var childMessage = repository.context(child.conversationId()).getFirst();
        String worker = UUID.randomUUID().toString(); assertThat(execution.claim(child.id(), worker)).isNotNull();
        assertCode(() -> evidenceStore.read(child.id(), worker, privateMessage.id()), "COPILOT_EVIDENCE_NOT_FOUND");
        assertCode(() -> evidenceStore.read(child.id(), worker, repository.context(exercise).getFirst().id()), "COPILOT_EVIDENCE_NOT_FOUND");
        tasks.stop(SESSION, child.id(), "stop-private-child"); execution.finish(child.id(), worker, "STOPPED", "USER_STOP");
        delegations.recover(parent.id()); tasks.stop(SESSION, parent.id(), "stop-parent-after-handoff");
        var binding = sql.getMapper(CopilotDelegationRepository.class).list(parent.id()).getFirst();
        var boundary = conversations.prepareFork(SESSION, exercise, exercise, binding.resultMessageId());
        var fork = conversations.fork(SESSION, exercise, CONTROL, "fork-delegation-result", boundary, measurement);
        var next = tasks.acceptInConversation(SESSION, exercise, CONTROL, "read-inherited-delegation", "Read original child evidence", true, List.of(), measurement, fork.id());
        String nextWorker = UUID.randomUUID().toString(); assertThat(execution.claim(next.id(), nextWorker)).isNotNull();
        assertThat(evidenceStore.read(next.id(), nextWorker, childMessage.id()).payload()).contains("Child goal");
        assertCode(() -> evidenceStore.read(next.id(), nextWorker, privateMessage.id()), "COPILOT_EVIDENCE_NOT_FOUND");
    }

    @Test
    void runnerAdmitsFullyEscapedBoundaryDelegationFieldsButBoundsTheirEnvelope() throws Exception {
        String goal = "\u0001".repeat(4095) + "G"; String context = "\u0002".repeat(8192);
        String arguments = json.writeValueAsString(Map.of("goal", goal, "context", context));
        assertThat(arguments.length()).isGreaterThan(12 * 1024).isLessThan(CopilotDelegationService.MAX_ARGUMENT_BYTES);
        var request = ToolExecutionRequest.builder().id("bounded-delegation").name("delegate_task").arguments(arguments).build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build(), 100));
        var parent = tasks.accept(SESSION, exercise, CONTROL, "bounded-envelope", "Delegate bounded context", true, measurement);
        runner.run(parent.id(), UUID.randomUUID().toString());
        assertThat(repository.find(parent.id()).state()).isEqualTo("WAITING_CHILD");
        var child = delegations.list(SESSION, parent.id()).getFirst().child();
        var retained = (UserMessage) ChatMessageDeserializer.messageFromJson(repository.context(child.conversationId()).getFirst().payload());
        assertThat(retained.singleText()).startsWith(goal).contains(json.writeValueAsString(context));
        tasks.stop(SESSION, child.id(), "stop-boundary-child"); delegations.recover(parent.id());
        var oversized = ToolExecutionRequest.builder().id("oversized-delegation").name("delegate_task")
            .arguments(" ".repeat(CopilotDelegationService.MAX_ARGUMENT_BYTES + 1)).build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(oversized))).build(), 100));
        runner.run(parent.id(), UUID.randomUUID().toString());
        assertThat(repository.find(parent.id()).reason()).isEqualTo("MODEL_INVALID_RESPONSE");
        assertThat(sql.getMapper(CopilotDelegationRepository.class).count(parent.id())).isEqualTo(1);
    }

    private CopilotTaskResponse beginDelegation() throws Exception {
        var request = ToolExecutionRequest.builder().id("delegate-first").name("delegate_task").arguments("{\"goal\":\"Child goal\"}").build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build(), 100));
        var parent = tasks.accept(SESSION, exercise, CONTROL, "delegate-start", "Parent goal", true, measurement);
        runner.run(parent.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, parent.id()).state()).isEqualTo("WAITING_CHILD");
        return parent;
    }

    @Test
    void btwCompletesWhileTheMainModelRequestIsStillInFlight() throws Exception {
        var main = tasks.accept(SESSION, exercise, CONTROL, "concurrent-main", "Main goal", true, measurement);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ChatRequest request = call.getArgument(1);
            boolean side = request.messages().stream().anyMatch(message -> message instanceof UserMessage user && user.singleText().startsWith("Side concurrent"));
            if (!side) { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(side ? "Side done" : "Main done")).build(), 100);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var primary = pool.submit(() -> runner.run(main.id(), UUID.randomUUID().toString()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var side = btw.start(SESSION, exercise, exercise, CONTROL, "concurrent-side", new CopilotBtwRequest("Side concurrent question", true));
                pool.submit(() -> runner.run(side.id(), UUID.randomUUID().toString())).get(5, TimeUnit.SECONDS);
                assertThat(repository.find(side.id()).state()).isEqualTo("SUCCEEDED"); assertThat(repository.find(main.id()).state()).isEqualTo("RUNNING");
            } finally { release.countDown(); }
            primary.get(5, TimeUnit.SECONDS);
        }
        assertThat(repository.find(main.id()).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void btwSummarySchedulingAndMainDispatchUseTheirOwnConversationGates() throws Exception {
        var main = tasks.accept(SESSION, exercise, CONTROL, "summary-main", "Main", true, measurement);
        String worker = UUID.randomUUID().toString(); assertThat(execution.claim(main.id(), worker)).isNotNull();
        var side = btw.start(SESSION, exercise, exercise, CONTROL, "summary-side", new CopilotBtwRequest("Side", true));
        jdbc.update("UPDATE copilot_task SET state='PAUSED' WHERE id=?::uuid", side.id());
        var summary = compactions.accept(SESSION, exercise, CONTROL, "side-summary", new CopilotCompactionRequest(true, side.conversationId()), measurement);
        var summaries = sql.getMapper(CopilotCompactionRepository.class);
        assertThat(summaries.runnableStandalone()).contains(summary.id());
        assertThat(summaryExecution.claim(summary.id(), UUID.randomUUID().toString(), null)).isNotNull();
        assertThat(execution.beginModel(main.id(), worker, contexts.beforeModel(main.id(), worker), measurement)).isNotNull();
        assertThat(repository.find(main.id()).state()).isEqualTo("RUNNING");
    }

    @Test
    void btwUnknownModelRecoveryKeepsItsIdentityAndNeverRestartsMainOrPaidDispatch() throws Exception {
        var main = tasks.accept(SESSION, exercise, CONTROL, "recovery-main", "Main", true, measurement);
        var input = new CopilotBtwRequest("Side recovery", true);
        var side = btw.start(SESSION, exercise, exercise, CONTROL, "recovery-side", input);
        String worker = UUID.randomUUID().toString();
        assertThat(execution.claim(side.id(), worker)).isNotNull();
        assertThat(execution.beginModel(side.id(), worker, contexts.beforeModel(side.id(), worker), measurement)).isNotNull();
        clock.now = clock.instant().plusSeconds(130); execution.recover(side.id());
        assertThat(tasks.find(SESSION, side.id()).reason()).isEqualTo("MODEL_RESULT_UNKNOWN");
        runner.run(side.id(), UUID.randomUUID().toString());
        assertThat(btw.start(SESSION, exercise, exercise, CONTROL, "recovery-side", input).id()).isEqualTo(side.id());
        assertThat(repository.find(main.id()).state()).isEqualTo("QUEUED");
        verify(driver, never()).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void btwStopDoesNotStopMainAndExpiryErasesBothConversationScopes() {
        var main = tasks.accept(SESSION, exercise, CONTROL, "btw-main", "Main", true, measurement);
        var side = btw.start(SESSION, exercise, exercise, CONTROL, "btw-side", new CopilotBtwRequest("Side", true));
        assertCode(() -> btw.start(SESSION, exercise, exercise, CONTROL, "another-side", new CopilotBtwRequest("Second", true)), "COPILOT_BUSY");
        tasks.stop(SESSION, side.id(), "stop-side"); execution.recover(side.id());
        assertThat(repository.find(side.id()).state()).isEqualTo("STOPPED");
        assertThat(repository.find(main.id()).state()).isEqualTo("QUEUED");
        assertCode(() -> btw.start(SESSION, exercise, exercise, "x".repeat(43), "stale-side", new CopilotBtwRequest("Stale", true)), "STALE_CONTROL");
        clock.now = clock.instant().plusSeconds(1801); execution.recover(main.id());
        assertCode(() -> btw.start(SESSION, exercise, exercise, CONTROL, "expired-side", new CopilotBtwRequest("Expired", true)), "INVALID_STATE");
        assertThat(repository.find(main.id()).state()).isNotIn("QUEUED", "RUNNING");
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(sql.getMapper(CopilotBtwRepository.class).find(exercise)).isNull();
        assertThat(repository.context(side.conversationId())).isEmpty();
    }

    @Test
    void referencesRetainVerifiedExcerptsAndReplayBeforeUnavailableSourceReads() {
        var page = references.page(SESSION, exercise, "CODE", "src/Main.java", null, 0, 5);
        var selected = new CopilotReferenceSelectionDTO(page.kind(), page.source(), page.version(), page.offset(), page.limit(), page.fingerprint());
        var input = new CopilotStartRequest("Inspect reference", true, List.of(), exercise, List.of(selected));
        var service = new CopilotTaskService(tasks, capacity, json, references);
        var accepted = service.start(SESSION, exercise, CONTROL, "reference-start", input);
        assertThat(repository.context(exercise).getFirst().payload()).contains("Verified reference excerpts", "src/Main.java", "class");
        doThrow(new IllegalStateException("Source is no longer reachable")).when(files).read(SESSION, exercise, "src/Main.java");
        assertThat(service.start(SESSION, exercise, CONTROL, "reference-start", input).id()).isEqualTo(accepted.id());
        verify(files, times(2)).read(SESSION, exercise, "src/Main.java"); // Preview and submit verification only; replay must not reread.
        assertCode(() -> service.start(SESSION, exercise, CONTROL, "reference-start", new CopilotStartRequest("Inspect reference", true)), "COPILOT_IDEMPOTENCY_CONFLICT");
        var other = conversations.create(SESSION, exercise, CONTROL, "reference-other", measurement);
        assertThat(tasks.messagesInConversation(SESSION, exercise, -1, other.id())).isEmpty();
    }

    @Test
    void referenceAdmissionRechecksSavedVersionAfterExternalPreparation() {
        var page = references.page(SESSION, exercise, "FILE", "src/Main.java", null, 0, 5);
        var selected = new CopilotReferenceSelectionDTO(page.kind(), page.source(), page.version(), page.offset(), page.limit(), page.fingerprint());
        var input = new CopilotStartRequest("Inspect", true, List.of(), exercise, List.of(selected));
        var prepared = references.prepare(SESSION, exercise, input.references());
        jdbc.update("UPDATE workspace_exercise SET revision=2 WHERE id=?::uuid", exercise);
        assertCode(() -> tasks.acceptPrepared(SESSION, exercise, CONTROL, "changed-source", input, prepared, measurement), "COPILOT_REFERENCE_CHANGED");
        assertThat(repository.receipt(exercise, "changed-source")).isNull();
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).hasMessage(code);
    }

    @Test
    void followupsRemainFifoIndependentAndIdempotentAcrossDispatcherReentry() throws Exception {
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("done")).build(), 100));
        var anchor = tasks.accept(SESSION, exercise, CONTROL, "anchor", "original", true, measurement);
        var firstInput = new CopilotFollowupRequest("first independent task", true);
        var first = followupService.enqueue(SESSION, exercise, exercise, CONTROL, "first", firstInput);
        var second = followupService.enqueue(SESSION, exercise, exercise, CONTROL, "second", new CopilotFollowupRequest("second independent task", true));
        assertThat(followupService.enqueue(SESSION, exercise, exercise, CONTROL, "first", firstInput).id()).isEqualTo(first.id());
        assertCode(() -> followupService.enqueue(SESSION, exercise, exercise, CONTROL, "first", new CopilotFollowupRequest("different", true)), "COPILOT_IDEMPOTENCY_CONFLICT");
        followupService.dispatch(first.id()); assertThat(followups.ready(first.id())).isNull();
        for (String state : List.of("PAUSED", "WAITING_APPROVAL", "WAITING_WRITE", "WAITING_TEST", "WAITING_COMMAND", "STOPPING")) {
            jdbc.update("UPDATE copilot_task SET state=? WHERE id=?::uuid", state, anchor.id());
            assertThat(followups.ready(first.id())).isNull();
        }
        jdbc.update("UPDATE copilot_task SET state='QUEUED' WHERE id=?::uuid", anchor.id());
        runner.run(anchor.id(), UUID.randomUUID().toString());
        followupService.dispatch(second.id()); assertThat(repository.latest(exercise).id()).isEqualTo(anchor.id());
        followupService.dispatch(first.id()); followupService.dispatch(first.id());
        var started = repository.latest(exercise);
        assertThat(started.id()).isNotEqualTo(anchor.id()); assertThat(started.conversationId()).isEqualTo(exercise);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_task WHERE exercise_id=?::uuid", Integer.class, exercise)).isEqualTo(2);
        assertCode(() -> followups.cancel(SESSION, exercise, exercise, first.id()), "COPILOT_FOLLOWUP_STARTED");
        runner.run(started.id(), UUID.randomUUID().toString()); followupService.dispatch(second.id());
        assertThat(followupService.recent(SESSION, exercise, exercise)).extracting(item -> item.state()).containsExactly("DISPATCHED", "DISPATCHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_task WHERE exercise_id=?::uuid", Integer.class, exercise)).isEqualTo(3);
        runner.run(repository.latest(exercise).id(), UUID.randomUUID().toString());
        var fresh = tasks.accept(SESSION, exercise, CONTROL, "fresh-anchor", "new independent goal", true, measurement);
        var later = followups.accept(SESSION, exercise, exercise, CONTROL, "later", new CopilotFollowupRequest("after fresh anchor", true), measurement);
        jdbc.update("UPDATE copilot_task SET state='FAILED',completed_at=? WHERE id=?::uuid", java.sql.Timestamp.from(clock.instant()), fresh.id());
        followupService.dispatch(later.id());
        assertThat(followups.recent(SESSION, exercise, exercise).getLast().state()).isEqualTo("PAUSED");
    }

    @Test
    void failedFollowupPausesLaterWorkUntilExplicitIdempotentReauthorization() {
        var anchor = tasks.accept(SESSION, exercise, CONTROL, "anchor", "original", true, measurement);
        var first = followups.accept(SESSION, exercise, exercise, CONTROL, "first", new CopilotFollowupRequest("first", true), measurement);
        var second = followups.accept(SESSION, exercise, exercise, CONTROL, "second", new CopilotFollowupRequest("second", true), measurement);
        assertCode(() -> followupService.resume(SESSION, exercise, exercise, CONTROL, "early-resume", true), "COPILOT_FOLLOWUP_NOT_ACTIVE");
        jdbc.update("UPDATE copilot_task SET state='STOPPED',completed_at=? WHERE id=?::uuid", java.sql.Timestamp.from(clock.instant()), anchor.id());
        followupService.dispatch(first.id()); followupService.dispatch(second.id());
        assertThat(followups.recent(SESSION, exercise, exercise)).extracting(item -> item.state()).containsExactly("PAUSED", "WAITING");
        var oldGeneration = sql.getMapper(CopilotFollowupRepository.class).find(first.id()).generation();
        followupService.resume(SESSION, exercise, exercise, CONTROL, "resume", true);
        followups.pause(first.id(), oldGeneration, "LATE_OLD_FAILURE");
        followupService.dispatch(first.id());
        var started = repository.latest(exercise);
        jdbc.update("UPDATE copilot_task SET state='FAILED',completed_at=? WHERE id=?::uuid", java.sql.Timestamp.from(clock.instant()), started.id());
        followupService.dispatch(second.id());
        assertThat(followups.recent(SESSION, exercise, exercise).getLast().state()).isEqualTo("PAUSED");
        followupService.resume(SESSION, exercise, exercise, CONTROL, "resume", true);
        assertThat(followups.recent(SESSION, exercise, exercise).getLast().state()).isEqualTo("PAUSED");
        followups.cancel(SESSION, exercise, exercise, second.id()); followups.cancel(SESSION, exercise, exercise, second.id());
        followupService.dispatch(second.id());
        assertThat(repository.latest(exercise).id()).isEqualTo(started.id());
        assertThat(followups.recent(SESSION, exercise, exercise).getLast().state()).isEqualTo("CANCELLED");
    }

    @Test
    void followupAuthorizationDeadlineCancellationAndDeletionStayInsideOriginalOwnership() {
        var anchor = tasks.accept(SESSION, exercise, CONTROL, "anchor", "original", true, measurement);
        var first = followups.accept(SESSION, exercise, exercise, CONTROL, "first", new CopilotFollowupRequest("first", true), measurement);
        var other = conversations.create(SESSION, exercise, CONTROL, "other", measurement);
        assertCode(() -> followups.cancel(SESSION, exercise, other.id(), first.id()), "COPILOT_NOT_FOUND");
        jdbc.update("UPDATE copilot_task SET state='SUCCEEDED',completed_at=? WHERE id=?::uuid", java.sql.Timestamp.from(clock.instant()), anchor.id());
        when(identity.authorizeMutation(SESSION)).thenThrow(new BusinessException(IdentityErrorCode.UNAUTHENTICATED));
        followupService.dispatch(first.id());
        assertThat(sql.getMapper(CopilotFollowupRepository.class).find(first.id()).state()).isEqualTo("PAUSED");
        clock.now = clock.instant().plusSeconds(1801);
        assertThat(followupService.pending()).contains(first.id()); followupService.dispatch(first.id());
        assertThat(sql.getMapper(CopilotFollowupRepository.class).find(first.id()).state()).isEqualTo("CANCELLED");
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(sql.getMapper(CopilotFollowupRepository.class).find(first.id())).isNull();
    }

    @Test
    void followupDiscoverySkipsThirtyTwoBlockedHeadsButStillFindsReadyAndExpiredWork() {
        for (int index = 0; index < 32; index++) seedBlockedFollowup(false);
        var anchor = tasks.accept(SESSION, exercise, CONTROL, "anchor", "original", true, measurement);
        var ready = followups.accept(SESSION, exercise, exercise, CONTROL, "ready", new CopilotFollowupRequest("ready", true), measurement);
        jdbc.update("UPDATE copilot_task SET state='SUCCEEDED',completed_at=? WHERE id=?::uuid", java.sql.Timestamp.from(clock.instant()), anchor.id());
        String expired = seedBlockedFollowup(true);
        assertThat(followupService.pending()).containsExactly(ready.id(), expired);
        followupService.dispatch(ready.id()); followupService.dispatch(expired);
        assertThat(sql.getMapper(CopilotFollowupRepository.class).find(ready.id()).state()).isEqualTo("DISPATCHED");
        assertThat(sql.getMapper(CopilotFollowupRepository.class).find(expired).state()).isEqualTo("CANCELLED");
    }

    private String seedBlockedFollowup(boolean expired) {
        String other = UUID.randomUUID().toString(), owner = UUID.randomUUID().toString(), instance = UUID.randomUUID().toString(), preparation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES(?::uuid,?,'fixture','USER',false)", owner, "q_" + owner.substring(0, 20));
        var exercises = sql.getMapper(ExerciseRepository.class);
        exercises.insert(new ExerciseEntity(other, owner, UUID.randomUUID().toString(), instance, preparation, "PREPARING", null, "[]", 1, clock.instant(), null, null, null, null, null));
        exercises.prepared(other, instance, preparation, 1, "[]");
        exercises.start(other, clock.instant().minusSeconds(60), clock.instant().plusSeconds(expired ? -1 : 1800));
        String task = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        repository.insert(new CopilotTaskEntity(task, other, owner, SESSION, "fixture", "fixture", "WAITING_APPROVAL", null, false, null, null, clock.instant(), null, other));
        sql.getMapper(CopilotFollowupRepository.class).insert(new CopilotFollowupEntity(id, 0, other, other, owner, SESSION, IdentitySecrets.digest(CONTROL), "fixture", "pending", task, null, "WAITING", null, null, clock.instant(), null, 0, 0));
        if (expired) jdbc.update("UPDATE copilot_followup SET state='PAUSED' WHERE id=?::uuid", id);
        return id;
    }

    @Test
    void followupsKeepOldPendingItemsVisibleAndEnforceBoundedAdmission() {
        tasks.accept(SESSION, exercise, CONTROL, "anchor", "original", true, measurement);
        var oldest = followups.accept(SESSION, exercise, exercise, CONTROL, "oldest", new CopilotFollowupRequest("oldest", true), measurement);
        for (int index = 0; index < 105; index++) {
            var item = followups.accept(SESSION, exercise, exercise, CONTROL, "cancel-" + index, new CopilotFollowupRequest("cancelled", true), measurement);
            followups.cancel(SESSION, exercise, exercise, item.id());
        }
        assertThat(followups.recent(SESSION, exercise, exercise)).hasSize(101).first().extracting(item -> item.id()).isEqualTo(oldest.id());
        for (int index = 0; index < 31; index++) followups.accept(SESSION, exercise, exercise, CONTROL, "pending-" + index, new CopilotFollowupRequest("pending", true), measurement);
        assertCode(() -> followups.accept(SESSION, exercise, exercise, CONTROL, "overflow", new CopilotFollowupRequest("overflow", true), measurement), "COPILOT_FOLLOWUP_LIMIT");
        assertThat(followups.recent(SESSION, exercise, exercise)).filteredOn(item -> item.state().equals("WAITING")).hasSize(32);
    }

    @Test
    void newConversationHasStableIdentityAndDoesNotMoveExistingTask() {
        var initial = conversations.list(SESSION, exercise);
        assertThat(initial).singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(exercise));
        var task = tasks.accept(SESSION, exercise, CONTROL, "initial-request", "Keep working", true, measurement);
        var created = conversations.create(SESSION, exercise, CONTROL, "new-conversation", measurement);
        assertThat(created.id()).isNotEqualTo(exercise);
        assertThat(conversations.create(SESSION, exercise, CONTROL, "new-conversation", measurement)).isEqualTo(created);
        assertThat(conversations.list(SESSION, exercise)).hasSize(2);
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("QUEUED");
    }

    @Test
    void newConversationSeparatesTaskHistoryAndPreparedModelContext() throws Exception {
        var original = tasks.accept(SESSION, exercise, CONTROL, "original-session", "ONLY_IN_OLD_CONVERSATION", true, measurement);
        var created = conversations.create(SESSION, exercise, CONTROL, "isolated-conversation", measurement);
        assertThat(tasks.messagesInConversation(SESSION, exercise, -1, created.id())).isEmpty();
        assertThat(tasks.latestInConversation(SESSION, exercise, created.id())).isNull();
        assertThatThrownBy(() -> tasks.acceptInConversation(SESSION, exercise, CONTROL, "busy-conversation", "new", true,
            List.of(), measurement, created.id())).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(CopilotErrorCode.COPILOT_BUSY));
        tasks.stop(SESSION, original.id(), "stop-original");
        var current = tasks.acceptInConversation(SESSION, exercise, CONTROL, "new-conversation-task", "ONLY_IN_NEW_CONVERSATION", true,
            List.of(), measurement, created.id());
        assertThat(tasks.messages(SESSION, exercise, -1)).singleElement().satisfies(message -> assertThat(message.taskId()).isEqualTo(original.id()));
        assertThat(tasks.messagesInConversation(SESSION, exercise, -1, created.id())).singleElement()
            .satisfies(message -> assertThat(message.taskId()).isEqualTo(current.id()));
        String worker = UUID.randomUUID().toString();
        assertThat(execution.claim(current.id(), worker)).isNotNull();
        var ticket = execution.contextTicket(current.id(), worker);
        assertThat(ticket.conversationId()).isEqualTo(created.id());
        String oldEvidence = tasks.messages(SESSION, exercise, -1).getFirst().id();
        assertThatThrownBy(() -> evidenceStore.read(current.id(), worker, oldEvidence)).isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(CopilotErrorCode.COPILOT_EVIDENCE_NOT_FOUND));
        var prepared = contextPreparation.prepare(ticket);
        assertThat(ChatMessageSerializer.messagesToJson(prepared.plan().projection().request().messages()))
            .contains("ONLY_IN_NEW_CONVERSATION").doesNotContain("ONLY_IN_OLD_CONVERSATION");
        assertThatThrownBy(() -> tasks.acceptInConversation(SESSION, exercise, CONTROL, "new-conversation-task", "ONLY_IN_NEW_CONVERSATION", true,
            List.of(), measurement, exercise)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> conversations.resolve(SESSION, exercise, UUID.randomUUID().toString()))
            .isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(CopilotErrorCode.COPILOT_CONVERSATION_NOT_FOUND));
    }

    @Test
    void forkInheritsNativePairsWithoutTasksAndKeepsBranchContextIndependent() throws Exception {
        var original = tasks.accept(SESSION, exercise, CONTROL, "fork-original", "ORIGINAL_GOAL", true, measurement);
        var call = ToolExecutionRequest.builder().id("old-call").name("bash").arguments("{}").build();
        String assistant = UUID.randomUUID().toString();
        String result = UUID.randomUUID().toString();
        repository.message(new CopilotMessageEntity(0, assistant, original.id(), exercise, "ASSISTANT",
            ChatMessageSerializer.messageToJson(AiMessage.builder().toolExecutionRequests(List.of(call)).thinking("retained-thinking").build()), clock.instant()));
        var service = new CopilotConversationService(conversations, capacity, new CopilotForkBoundary());
        assertThatThrownBy(() -> service.fork(SESSION, exercise, CONTROL, "invalid-fork", new CopilotForkRequest(UUID.fromString(exercise), UUID.fromString(assistant))))
            .isInstanceOf(BusinessException.class).hasMessage("COPILOT_FORK_BOUNDARY");
        repository.message(new CopilotMessageEntity(0, result, original.id(), exercise, "TOOL", ChatMessageSerializer.messageToJson(ToolExecutionResultMessage.from(call, "OLD_COMMAND_RESULT")), clock.instant()));
        var input = new CopilotForkRequest(UUID.fromString(exercise), UUID.fromString(result));
        var branch = service.fork(SESSION, exercise, CONTROL, "valid-fork", input);
        assertThat(branch.forkSource()).isEqualTo(exercise); assertThat(branch.forkMessage()).isEqualTo(result);
        assertThat(service.fork(SESSION, exercise, "expired-control", "valid-fork", input)).isEqualTo(branch);
        assertThatThrownBy(() -> service.create(SESSION, exercise, CONTROL, "valid-fork")).isInstanceOf(BusinessException.class).hasMessage("COPILOT_IDEMPOTENCY_CONFLICT");
        assertThat(tasks.latestInConversation(SESSION, exercise, branch.id())).isNull();
        assertThat(repository.context(branch.id())).hasSize(3);
        assertThat(repository.find(original.id()).state()).isEqualTo("QUEUED");
        assertThat(sql.getMapper(ExerciseRepository.class).find(exercise).revision()).isEqualTo(1);
        verify(driver, never()).call(any(), any(), any(), anyInt(), any(), any());
        String later = UUID.randomUUID().toString();
        repository.message(new CopilotMessageEntity(0, later, original.id(), exercise, "ASSISTANT", ChatMessageSerializer.messageToJson(AiMessage.from("PARENT_LATER")), clock.instant()));
        assertThat(repository.context(branch.id())).hasSize(3);
        tasks.stop(SESSION, original.id(), "stop-for-fork");
        var child = tasks.acceptInConversation(SESSION, exercise, CONTROL, "fork-child", "CHILD_GOAL", true, List.of(), measurement, branch.id());
        String worker = UUID.randomUUID().toString(); execution.claim(child.id(), worker);
        var prepared = contexts.beforeModel(child.id(), worker);
        String messages = ChatMessageSerializer.messagesToJson(prepared.plan().projection().request().messages());
        assertThat(messages).contains("ORIGINAL_GOAL", "OLD_COMMAND_RESULT", "retained-thinking", "CHILD_GOAL").doesNotContain("PARENT_LATER");
        assertThat(evidenceStore.read(child.id(), worker, result).payload()).contains("OLD_COMMAND_RESULT");
        assertThatThrownBy(() -> evidenceStore.read(child.id(), worker, later)).isInstanceOf(BusinessException.class).hasMessage("COPILOT_EVIDENCE_NOT_FOUND");
        assertThat(repository.context(exercise)).hasSize(4);
        var nested = service.fork(SESSION, exercise, CONTROL, "nested-fork", new CopilotForkRequest(UUID.fromString(branch.id()), UUID.fromString(result)));
        assertThat(repository.context(nested.id())).hasSize(3);
        tasks.stop(SESSION, child.id(), "stop-child"); execution.finish(child.id(), worker, "STOPPED", "USER_STOP");
        new TransactionTemplate(transactions).executeWithoutResult(status -> data.erase(exercise));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_fork_message WHERE exercise_id=?::uuid", Integer.class, exercise)).isZero();
        assertThat(repository.context(branch.id())).isEmpty();
    }

    @Test
    void conversationLimitAndEndedExerciseRejectNewWritesButKeepHistoryReadable() {
        var first = conversations.create(SESSION, exercise, CONTROL, "retained-conversation", measurement);
        jdbc.update("INSERT INTO copilot_conversation(id,exercise_id,request_key,created_at) SELECT gen_random_uuid(),?::uuid,'seed-' || n,now() FROM generate_series(1,98) n", exercise);
        assertThat(conversations.list(SESSION, exercise)).hasSize(100);
        assertThat(conversations.create(SESSION, exercise, CONTROL, "retained-conversation", measurement)).isEqualTo(first);
        assertThat(new CopilotConversationService(conversations, capacity, new CopilotForkBoundary()).create(SESSION, exercise, "expired-control", "retained-conversation"))
            .isEqualTo(first);
        assertThatThrownBy(() -> conversations.create(SESSION, exercise, CONTROL, "one-too-many", measurement))
            .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(CopilotErrorCode.COPILOT_CONVERSATION_LIMIT));
        jdbc.update("DELETE FROM copilot_conversation WHERE request_key LIKE 'seed-%'");
        jdbc.update("UPDATE workspace_exercise SET state='ENDED' WHERE id=?::uuid", exercise);
        assertThat(conversations.list(SESSION, exercise)).hasSize(2);
        assertThatThrownBy(() -> conversations.create(SESSION, exercise, CONTROL, "after-end", measurement)).isInstanceOf(BusinessException.class);
        assertThat(conversations.list(SESSION, exercise)).hasSize(2);
    }

    @Test
    void publishedSummaryAndManualCompactionsRemainInTheirConversation() throws Exception {
        String worker = UUID.randomUUID().toString();
        var dispatch = dispatchedSummary(worker);
        summaryExecution.complete(dispatch.call().compactionId(), worker, dispatch.call().id(), summaryReceipt(dispatch));
        var created = conversations.create(SESSION, exercise, CONTROL, "new-after-summary", measurement);
        var empty = compactions.currentInConversation(SESSION, exercise, created.id());
        assertThat(empty.version()).isEqualTo("0");
        assertThat(empty.summary()).isNull();
        assertThat(empty.requests()).isEmpty();
        assertThat(usage.currentInConversation(SESSION, exercise, created.id()).context().state()).isEqualTo("UNKNOWN");
        var request = new CopilotCompactionRequest(true, created.id());
        var job = compactions.accept(SESSION, exercise, CONTROL, "compact-new", request, measurement);
        summaryRunner.runStandalone(job.id());
        assertThat(compactions.currentInConversation(SESSION, exercise, created.id()).requests()).singleElement()
            .satisfies(item -> assertThat(item.state()).isEqualTo("NOT_NEEDED"));
        assertThat(compactions.current(SESSION, exercise).version()).isEqualTo("1");
        assertThat(compactions.current(SESSION, exercise).requests()).hasSize(1);
    }

    @Test
    void webToolResultReturnsToOriginalLoopAndRecoveryDoesNotRepeatTheCall() throws Exception {
        String body = "{\"code\":\"OK\",\"kind\":\"SEARCH_SNIPPETS\",\"sources\":[{\"url\":\"https://example.com/docs\",\"content\":\"confirmed snippet\"}]}";
        when(webTools.execute(SESSION, exercise, "web_search", "{\"query\":\"docs\"}")).thenReturn(body);
        var count = new AtomicInteger();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            if (count.getAndIncrement() == 0) return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("web-call").name("web_search").arguments("{\"query\":\"docs\"}").build()))).build(), 100);
            ChatRequest request = call.getArgument(1);
            assertThat(ChatMessageSerializer.messagesToJson(request.messages())).contains("confirmed snippet", "https://example.com/docs");
            return new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("source-based answer")).build(), 100);
        });
        var task = tasks.accept(SESSION, exercise, CONTROL, "web-task", "Search docs", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        runner.run(task.id(), UUID.randomUUID().toString());
        verify(webTools, times(1)).execute(SESSION, exercise, "web_search", "{\"query\":\"docs\"}");
        assertThat(repository.context(exercise)).anyMatch(message -> message.payload().contains("confirmed snippet"));
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
    void expiredDispatchedRequestIsInterruptedWithoutAnotherPaidCall() throws Exception {
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Inspect", true, measurement);
        String worker = UUID.randomUUID().toString();
        assertThat(execution.claim(task.id(), worker)).isNotNull();
        assertThat(execution.beginModel(task.id(), worker, contexts.beforeModel(task.id(), worker), measurement)).isNotNull();
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allAiEvidenceUsesTheExistingManualOrThirtyDayDeletionBoundary(boolean expiry) throws Exception {
        String owner = identity.authorization(SESSION).userId();
        var keep = personalSkills.save(SESSION, null, "retain-keep", null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", "---\nname: keep\ndescription: Keep original\n---\nPersonal retained guidance"))));
        personalSkills.change(SESSION, keep.id(), "retain-enable-keep", keep.version(), true, false);
        var remove = personalSkills.save(SESSION, null, "retain-remove", null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", "---\nname: remove\ndescription: Snapshot only\n---\nSnapshot retained guidance"))));
        var enabled = personalSkills.change(SESSION, remove.id(), "retain-enable-remove", remove.version(), true, false);
        String summaryWorker = UUID.randomUUID().toString(); var summary = dispatchedSummary(summaryWorker);
        summaryExecution.complete(summary.call().compactionId(), summaryWorker, summary.call().id(), summaryReceipt(summary));
        clearInvocations(driver);
        var workspace = new ProposalWorkspace(); var task = beginProposal(workspace, true);
        personalSkills.change(SESSION, remove.id(), "retain-delete-original", enabled.version(), false, true);
        assertThat(skillSnapshots.list(owner, task.id())).hasSize(2);
        var personalBefore = connections.connections(SESSION, null);
        String connection = sql.getMapper(ModelConfigurationRepository.class).find(alternateModelId).connectionId();
        var credentialBefore = sql.getMapper(ModelConnectionRepository.class).find(connection).credential();
        var endedAt = clock.instant(); var retainUntil = endedAt.plus(Duration.ofDays(30));
        // Fixture represents an ended exercise with an outstanding AI batch; no physical cleanup is assumed.
        jdbc.update("UPDATE workspace_exercise SET state='ENDED',ended_at=?,retain_until=? WHERE id=?::uuid", Timestamp.from(endedAt), Timestamp.from(retainUntil), exercise);
        var retention = new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60);
        var deletion = proxy(new DeletionStore(sql.getMapper(DeletionRepository.class), sql.getMapper(ExerciseRepository.class), identity, clock, retention, data), DeletionStore.class);
        if (expiry) {
            clock.now = retainUntil.minusSeconds(1); deletion.expire(exercise);
            assertThat(sql.getMapper(ExerciseRepository.class).deleted(exercise)).isFalse();
            assertThat(tasks.messages(SESSION, exercise, -1)).isNotEmpty();
            clock.now = retainUntil; deletion.expire(exercise);
        } else deletion.request(SESSION, exercise, "retain-delete");
        assertThatThrownBy(() -> tasks.find(SESSION, task.id())).hasMessage("EXERCISE_DELETED");
        var foreign = new SessionAuthorizationDTO(UUID.randomUUID().toString(), "USER", false, 1, clock.instant().plusSeconds(3600));
        when(identity.authorization("retention-foreign")).thenReturn(foreign);
        assertThatThrownBy(() -> deletion.status("retention-foreign", exercise)).hasMessage("RESOURCE_NOT_FOUND");
        long retained = data.retainedBytes(exercise); assertThat(retained).isPositive();
        assertThat(deletion.reconciled(exercise)).isFalse();
        String claim = UUID.randomUUID().toString(); assertThat(deletion.claim(exercise, claim)).isTrue();
        assertThatThrownBy(() -> deletion.complete(exercise, claim)).isInstanceOf(IllegalStateException.class);
        assertThat(data.retainedBytes(exercise)).isEqualTo(retained);
        deletion.failed(sql.getMapper(DeletionRepository.class).find(exercise), claim, "AI_RECOVERY_PENDING");
        clock.now = clock.now.plus(Duration.ofHours(24));
        assertThat(deletion.status(SESSION, exercise).interventionRequired()).isTrue();
        assertThatThrownBy(() -> tasks.messages(SESSION, exercise, -1)).hasMessage("EXERCISE_DELETED");
        execution.recover(task.id());
        assertThat(workspace.applies.get()).isZero();
        assertThat(data.quiescent(exercise)).isTrue();
        var restarted = proxy(new DeletionStore(sql.getMapper(DeletionRepository.class), sql.getMapper(ExerciseRepository.class), identity, clock, retention, data), DeletionStore.class);
        assertThat(restarted.reconciled(exercise)).isTrue();
        String retry = UUID.randomUUID().toString(); assertThat(restarted.claim(exercise, retry)).isTrue();
        restarted.complete(exercise, retry);
        assertThat(data.retainedBytes(exercise)).isZero();
        assertThat(skillSnapshots.list(owner, task.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_skill_version WHERE id=?::uuid", Integer.class, remove.contentVersion())).isZero();
        assertThat(personalSkills.list(SESSION)).singleElement().satisfies(skill -> assertThat(skill.id()).isEqualTo(keep.id()));
        assertThat(connections.connections(SESSION, null)).isEqualTo(personalBefore);
        assertThat(sql.getMapper(ModelConnectionRepository.class).find(connection).credential().equals(credentialBefore)).isTrue();
        assertThat(restarted.status(SESSION, exercise).state()).isEqualTo("SUCCEEDED");
        clock.now = clock.now.plus(Duration.ofDays(7)); restarted.expireReceipts();
        assertThatThrownBy(() -> restarted.status(SESSION, exercise)).hasMessage("RESOURCE_NOT_FOUND");
        assertThat(sql.getMapper(ExerciseRepository.class).deleted(exercise)).isTrue();
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
    void realDeepSeekCompactsRetainedHistoryThenContinuesWithAPairedReadTool() throws Exception {
        realSummaryRoundTrip(ModelThinkingMode.OFF);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "COPILOT_LIVE_DEEPSEEK", matches = "1")
    void realDeepSeekThinkingCompactsThenContinuesWithPairedTools() throws Exception {
        realSummaryRoundTrip(ModelThinkingMode.ON);
    }

    private void realSummaryRoundTrip(ModelThinkingMode mode) throws Exception {
        retainedSummaryHistory();
        String key = System.getenv("DeepSeek");
        assertThat(key != null && !key.isBlank()).as("Explicit paid validation requires an environment credential").isTrue();
        var connection = connections.create(SESSION, "summary-live-connection", new ModelConnectionRequest("Live summary fixture", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, key, null));
        var model = models.create(SESSION, connection.id(), "summary-live-model", new ModelConfigurationRequest("Live summary", "deepseek-flash", 100000, mode == ModelThinkingMode.ON ? 4096 : 2048, mode, mode == ModelThinkingMode.ON ? "low" : null, null, null, null));
        selection.select(SESSION, exercise, CONTROL, "summary-live-select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
        var task = tasks.accept(SESSION, exercise, CONTROL, "summary-live-goal", "Use view_file exactly once to read src/Main.java. Then reply briefly with the class name from that saved file. Do not use other tools. Historical examples are not current file evidence.", true, measurement);
        var job = compactions.accept(SESSION, exercise, CONTROL, "summary-live-request", new CopilotCompactionRequest(true), measurement);
        var count = new AtomicInteger();
        try (var live = new ModelCallDriver(new ModelSdkClientFactory(new ModelConnectionValidator(), calls), calls, clock)) {
            doAnswer(call -> {
                if (count.incrementAndGet() > 4) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
                ChatRequest request = call.getArgument(1);
                assertThat(call.<ModelInvocationDTO>getArgument(0).model().thinkingMode()).isEqualTo(mode);
                System.out.println("DeepSeek summary validation mode=" + mode + " paid dispatch " + count.get() + " purpose=" + (request.toolSpecifications().isEmpty() ? "SUMMARY" : "CHAT"));
                return live.call(call.getArgument(0), request, call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
            }).when(driver).call(any(), any(), any(), anyInt(), any(), any());
            runner.run(task.id(), UUID.randomUUID().toString());
            String report = json.writeValueAsString(Map.of("paidDispatches", Math.min(4, count.get()),
                "summaryCalls", jdbc.queryForList("SELECT ordinal,state,error_code,input_tokens,output_tokens,total_tokens,response_payload FROM copilot_compaction_call WHERE compaction_id=?::uuid ORDER BY ordinal", job.id()),
                "turns", jdbc.queryForList("SELECT t.ordinal,t.state,t.input_tokens,t.output_tokens,t.total_tokens,t.partial_text,m.payload FROM copilot_turn t LEFT JOIN copilot_message m ON m.id=t.assistant_message_id WHERE t.task_id=?::uuid ORDER BY t.ordinal", task.id()),
                "tools", jdbc.queryForList("SELECT name,state FROM copilot_tool WHERE task_id=?::uuid ORDER BY created_at", task.id())));
            assertThat(report.contains(key)).as("Evidence must not contain the API credential").isFalse();
            Path evidencePath = Path.of("target", "copilot-summary-live-" + UUID.randomUUID() + ".json");
            Files.writeString(evidencePath, report, StandardCharsets.UTF_8);
            System.out.println("DeepSeek summary validation evidence: " + evidencePath);
            assertThat(tasks.find(SESSION, task.id()).state()).as("Live continuation reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("SUCCEEDED");
            assertThat(compactions.current(SESSION, exercise).requests().getFirst().state()).isEqualTo("APPLIED");
            assertThat(compactions.current(SESSION, exercise).summaryId()).isEqualTo(job.id());
            verify(files, times(1)).read(SESSION, exercise, "src/Main.java");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_tool WHERE task_id=?::uuid AND state='SUCCEEDED'", Integer.class, task.id())).isEqualTo(1);
            assertThat(tasks.messages(SESSION, exercise, -1).getLast().payload()).contains("Main");
            assertThat(count.get()).isBetween(3, 4);
            var usage = jdbc.queryForList("SELECT 'SUMMARY' AS purpose,input_tokens,output_tokens,total_tokens,usage_incomplete FROM copilot_compaction_call WHERE compaction_id=?::uuid UNION ALL SELECT 'CHAT',input_tokens,output_tokens,total_tokens,usage_incomplete FROM copilot_turn WHERE task_id=?::uuid", job.id(), task.id());
            assertThat(usage).hasSize(count.get());
            for (var sample : usage) assertThat(sample.get("input_tokens")).isNotNull();
            assertThat(jdbc.queryForList("SELECT configuration_payload FROM copilot_compaction_call UNION ALL SELECT configuration_payload FROM copilot_turn", String.class).stream()
                .filter(value -> value != null).anyMatch(value -> value.contains(key))).isFalse();
            System.out.println("DeepSeek summary validation: fixture history/Go reads, real SDK summary and continuation; paid usage only: " + usage);
        }
        // The isolated fixture database owns this connection and is erased by the suite, including on assertion failure.
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "COPILOT_LIVE_DEEPSEEK", matches = "1")
    void realDeepSeekLoadsPinnedSkillGuidanceOnDemandAndUsesItsPrivateMarker() throws Exception {
        String key = System.getenv("DeepSeek");
        assertThat(key != null && !key.isBlank()).as("Explicit DeepSeek credential is required").isTrue();
        String marker = "SKILL_OK_" + UUID.randomUUID().toString().substring(0, 8);
        var original = personalSkills.save(SESSION, null, "live-skill", null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md",
            "---\nname: response-verification\ndescription: Exact response verification guidance\n---\nReply with exactly this text and nothing else: " + marker))));
        personalSkills.change(SESSION, original.id(), "live-skill-enable", original.version(), true, false);
        var connection = connections.create(SESSION, "live-connection", new ModelConnectionRequest("Live skill fixture", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, key, null));
        var model = models.create(SESSION, connection.id(), "live-model", new ModelConfigurationRequest("Live skill fixture", "deepseek-flash", 100000, 1024, ModelThinkingMode.OFF, null, null, null, null));
        selection.select(SESSION, exercise, CONTROL, "live-select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
        var count = new AtomicInteger();
        try (var live = new ModelCallDriver(new ModelSdkClientFactory(new ModelConnectionValidator(), calls), calls, clock)) {
            doAnswer(call -> {
                if (count.incrementAndGet() > 3) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
                return live.call(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
            }).when(driver).call(any(), any(), any(), anyInt(), any(), any());
            var task = tasks.accept(SESSION, exercise, CONTROL, "live-skill-task", "Use load_skill once to read SKILL.md from the available response-verification Skill, then follow its exact response guidance. Do not use any other tool.", true, measurement);
            runner.run(task.id(), UUID.randomUUID().toString());
            assertThat(tasks.find(SESSION, task.id()).state()).as("live task reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("SUCCEEDED");
            var messages = tasks.messages(SESSION, exercise, -1);
            assertThat(messages).anySatisfy(message -> assertThat(message.payload()).contains("load_skill", marker));
            assertThat(messages.getLast().payload()).contains(marker);
            assertThat(count.get()).isBetween(2, 3);
            System.out.println("DeepSeek Skill paid requests=" + count.get() + "; numeric usage=" + jdbc.queryForList("SELECT input_tokens,output_tokens,total_tokens FROM copilot_turn WHERE task_id=?::uuid ORDER BY ordinal", task.id()));
        } finally { connections.delete(SESSION, connection.id(), "live-cleanup", connection.version()); }
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
        try (var coordinator = new CopilotCoordinator(repository, execution, runner, limits, clock, proposals, testTools, automatic, sql.getMapper(CopilotCompactionRepository.class), summaryExecution, summaryRunner, commandTools, mock(CopilotFollowupService.class), delegations)) {
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
        runner = new CopilotRunner(execution, capacity, driver, executor, limits, calls, contexts);
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
        var change = sql.getMapper(WorkspaceHistoryRepository.class).recent(exercise, 0).getFirst();
        assertThat(change.source()).isEqualTo("AI_PATCH");
        assertThat(change.taskId()).isEqualTo(task.id());
        assertThat(change.conversationId()).isEqualTo(repository.find(task.id()).conversationId());
        assertThat(change.baseRevision()).isEqualTo(1);
        assertThat(change.resultRevision()).isEqualTo(2);
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

    @ParameterizedTest
    @CsvSource({"OPENAI_CHAT,false", "OPENAI_CHAT,true", "OPENAI_RESPONSES,true", "ANTHROPIC,true"})
    void stopBeforeApprovalCancelsTheProposalAndPreservesCompletedReads(ModelProtocol protocol, boolean thinking) throws Exception {
        var workspace = new ProposalWorkspace();
        var task = beginProposal(workspace, true, thinking, protocol);
        String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
        tasks.stop(SESSION, task.id(), "stop");
        assertThat(proposals.decide(SESSION, proposal, CONTROL, "late-approve", new CopilotProposalDecisionRequest("APPROVE", null), measurement).state()).isEqualTo("CANCELLED");
        execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(workspace.applies.get()).isZero();
        verify(files, times(2)).read(SESSION, exercise, ProposalWorkspace.PATH);
        assertThat(tasks.messages(SESSION, exercise, -1)).hasSize(5);
        if (thinking) {
            var retained = (AiMessage) ChatMessageDeserializer.messageFromJson(tasks.messages(SESSION, exercise, -1).get(1).payload());
            assertThat(retained.thinking()).isEqualTo("opaque-fixture-thinking");
            assertThat(retained.attributes()).containsAllEntriesOf(protocolAttributes(protocol));
        }
        new CopilotRunner(execution, capacity, driver, executor, limits, calls, contexts).run(task.id(), UUID.randomUUID().toString());
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
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
        realProposalRoundTrip(ModelThinkingMode.OFF, "APPROVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT", "AUTO"})
    @EnabledIfEnvironmentVariable(named = "COPILOT_LIVE_DEEPSEEK", matches = "1")
    void realDeepSeekThinkingPreservesReasoningAcrossApprovalRejectionAndAuto(String decision) throws Exception {
        String selected = System.getenv("COPILOT_LIVE_DECISION");
        assumeTrue(selected == null || selected.equals(decision));
        realProposalRoundTrip(ModelThinkingMode.ON, decision);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI_RESPONSES", "ANTHROPIC"})
    @EnabledIfEnvironmentVariable(named = "COPILOT_LIVE_DEEPSEEK", matches = "1")
    void realDeepSeekAlternateProtocolsResumeAnApprovedPersistedBatch(String protocol) throws Exception {
        String selected = System.getenv("COPILOT_LIVE_PROTOCOL");
        assumeTrue(selected == null || selected.equals(protocol));
        realProposalRoundTrip(ModelThinkingMode.OFF, "APPROVE", ModelProtocol.valueOf(protocol));
    }

    private void realProposalRoundTrip(ModelThinkingMode mode, String decision) throws Exception {
        realProposalRoundTrip(mode, decision, ModelProtocol.OPENAI_CHAT);
    }

    private void realProposalRoundTrip(ModelThinkingMode mode, String decision, ModelProtocol protocol) throws Exception {
        var workspace = new ProposalWorkspace();
        String key = System.getenv("DeepSeek");
        assertThat(key != null && !key.isBlank()).as("DeepSeek credential must be supplied through the environment").isTrue();
        var connection = connections.create(SESSION, "live-connection", new ModelConnectionRequest("Live proposal fixture", protocol == ModelProtocol.ANTHROPIC ? "https://api.deepseek.com/anthropic/v1" : "https://api.deepseek.com", protocol, ModelProviderPreset.DEEPSEEK, key, null));
        var count = new AtomicInteger();
        var nativeAssistants = new ArrayList<String>();
        try (var live = new ModelCallDriver(new ModelSdkClientFactory(new ModelConnectionValidator(), calls), calls, clock)) {
            var model = models.create(SESSION, connection.id(), "live-model", new ModelConfigurationRequest("Live proposal fixture", "deepseek-flash", 100000, mode == ModelThinkingMode.ON ? 4096 : 2048, mode, mode == ModelThinkingMode.ON ? "low" : null, null, null, null));
            selection.select(SESSION, exercise, CONTROL, "live-select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
            doAnswer(call -> {
                if (count.incrementAndGet() > 8) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
                assertThat(call.<ModelInvocationDTO>getArgument(0).model().thinkingMode()).isEqualTo(mode);
                var result = live.call(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5));
                nativeAssistants.add(ChatMessageSerializer.messageToJson(result.response().aiMessage()));
                return result;
            }).when(driver).call(any(), any(), any(), anyInt(), any(), any());
            if ("AUTO".equals(decision)) approvalModes.change(SESSION, exercise, CONTROL, "live-auto", new CopilotApprovalModeRequest("AUTO", "0"));
            var task = tasks.accept(SESSION, exercise, CONTROL, "live-start", "Read src/main/java/Main.java. Propose exactly one UPDATE replacing its entire content with: class Main { int answer = 42; }. Wait for approval. After its confirmed application, use view_file to read the saved file, then reply briefly with the value of answer. If rejected, reply REJECTED and do not propose another edit.", true, measurement);
            runner.run(task.id(), UUID.randomUUID().toString());
            assertThat(tasks.find(SESSION, task.id()).state()).as("live proposal reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("WAITING_APPROVAL");
            assertThat(workspace.applies.get()).isZero();
            String proposal = proposals.list(SESSION, task.id(), 0).items().getFirst().id();
            assertThat(proposalViews.detail(SESSION, proposal).files().getFirst().after()).contains("42");
            String retainedAssistant = repository.messageById(repository.latestTurn(task.id()).assistantMessageId()).payload();
            if ("AUTO".equals(decision)) automatic.apply(task.id());
            else proposals.decide(SESSION, proposal, CONTROL, "live-decision", new CopilotProposalDecisionRequest(decision, "REJECT".equals(decision) ? "Fixture rejection" : null), measurement);
            writeCoordinator.recover(); proposals.recoverTask(task.id());
            new CopilotRunner(execution, capacity, driver, executor, limits, calls, contexts).run(task.id(), UUID.randomUUID().toString());
            assertThat(tasks.messages(SESSION, exercise, -1)).anySatisfy(message -> assertThat(message.payload()).isEqualTo(retainedAssistant));
            assertThat(tasks.find(SESSION, task.id()).state()).as("live continuation reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("SUCCEEDED");
            assertThat(workspace.applies.get()).isEqualTo("REJECT".equals(decision) ? 0 : 1);
            assertThat(tasks.messages(SESSION, exercise, -1).getLast().payload()).contains("REJECT".equals(decision) ? "REJECTED" : "42");
            if (!"REJECT".equals(decision)) assertThat(proposals.summary(SESSION, proposal).resultRevision()).isEqualTo("2");
            assertThat(tasks.messages(SESSION, exercise, -1).stream().filter(message -> "ASSISTANT".equals(message.kind())).map(message -> message.payload()).toList())
                .containsExactlyElementsOf(nativeAssistants);
            var thinkingLengths = nativeAssistants.stream().map(payload -> ((AiMessage) ChatMessageDeserializer.messageFromJson(payload)).thinking())
                .map(value -> value == null ? 0 : value.length()).toList();
            System.out.println("DeepSeek native reasoning lengths mode=" + mode + " decision=" + decision + ": " + thinkingLengths);
            if (mode == ModelThinkingMode.ON) assertThat(thinkingLengths).anyMatch(length -> length > 0);
            var samples = jdbc.queryForList("SELECT input_tokens,output_tokens,total_tokens,usage_payload FROM copilot_turn WHERE task_id=?::uuid ORDER BY ordinal", task.id());
            for (var sample : samples) assertThat(sample.get("usage_payload")).isNotNull();
            System.out.println("DeepSeek live proposal protocol=" + protocol + " mode=" + mode + " decision=" + decision + " requests=" + count.get()
                + " (Go is a deterministic fixture; no credentials or request bodies): " + samples);
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

    @Test
    void bashAskRejectPairsTheOriginalCallAndContinuesWithoutAnExecution() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        assertThat(command.mode()).isEqualTo("ASK");
        assertThat(repository.find(task.id()).workerId()).isNull();
        commandTools.decide(SESSION, command.id(), CONTROL, "reject-command", new CopilotProposalDecisionRequest("REJECT", "Use Java APIs instead"));
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_command WHERE exercise_id=?::uuid", Integer.class, exercise)).isZero();
        assertThat(repository.context(exercise).stream().filter(message -> "TOOL".equals(message.kind())).toList()).singleElement()
            .satisfies(message -> assertThat(message.payload()).contains("bash-first", "COMMAND_REJECTED", "Use Java APIs instead"));
    }

    @Test
    void bashOldAskIsNotApprovedByModeSwitchAndDecisionReplayDoesNotRequireNewControl() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        approvalModes.change(SESSION, exercise, CONTROL, "auto-now", new CopilotApprovalModeRequest("AUTO", "0"));
        commandTools.approveAuto(task.id());
        assertThat(commandTools.read(SESSION, command.id()).state()).isEqualTo("PENDING");
        var approved = commandTools.decide(SESSION, command.id(), CONTROL, "approve-command", new CopilotProposalDecisionRequest("APPROVE", null));
        assertThat(approved.commandTaskId()).isNotNull();
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_COMMAND");
        assertThat(commandTools.decide(SESSION, command.id(), "no-longer-current", "approve-command", new CopilotProposalDecisionRequest("APPROVE", null)).commandTaskId())
            .isEqualTo(approved.commandTaskId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_command WHERE exercise_id=?::uuid", Integer.class, exercise)).isEqualTo(1);
    }

    @Test
    void bashRegisteredAutoKeepsItsModeVersionAndBindsExactlyOnce() throws Exception {
        approvalModes.change(SESSION, exercise, CONTROL, "auto-before", new CopilotApprovalModeRequest("AUTO", "0"));
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        assertThat(command.modeVersion()).isEqualTo("1");
        approvalModes.change(SESSION, exercise, CONTROL, "ask-after", new CopilotApprovalModeRequest("ASK", "1"));
        commandTools.approveAuto(task.id());
        commandTools.approveAuto(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_COMMAND");
        assertThat(commandTools.read(SESSION, command.id()).mode()).isEqualTo("AUTO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_command WHERE exercise_id=?::uuid", Integer.class, exercise)).isEqualTo(1);
    }

    @Test
    void bashBusyNeverAdoptsOrStopsTheIndependentManualCommand() throws Exception {
        var manual = childCommands.accept(SESSION, exercise, "manual-command", CONTROL, new CommandStartRequest("echo manual", ".", "1"), measurement);
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        var result = commandTools.decide(SESSION, command.id(), CONTROL, "approve-busy", new CopilotProposalDecisionRequest("APPROVE", null));
        assertThat(result.result().code()).isEqualTo("COMMAND_SLOT_BUSY");
        assertThat(result.commandTaskId()).isNull();
        tasks.stop(SESSION, task.id(), "stop-parent");
        assertThat(childCommands.find(manual.id()).stopRequested()).isFalse();
    }

    @Test
    void bashUnknownStartIsNotReplayedAndParentStopWaitsForItsExactCancellationProof() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        var approved = commandTools.decide(SESSION, command.id(), CONTROL, "approve-unknown", new CopilotProposalDecisionRequest("APPROVE", null));
        var child = childCommands.find(approved.commandTaskId());
        var coordinator = new CommandTaskCoordinator(childCommands, sandbox);
        when(sandbox.inventory(anyString(), anyString())).thenReturn(ExecutionInventory.newBuilder().setHostEpoch("A".repeat(26)).build());
        when(sandbox.startCommand(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        assertThatThrownBy(() -> coordinator.observe(child)).isInstanceOf(StatusRuntimeException.class);
        tasks.stop(SESSION, task.id(), "stop-command-parent");
        when(sandbox.stopCommand(any())).thenReturn(StopTaskResponse.getDefaultInstance());
        assertThatThrownBy(() -> coordinator.observe(childCommands.find(child.id()))).isInstanceOf(IllegalStateException.class);
        commandTools.recoverTask(task.id()); execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPING");
        assertThat(data.protectedCommandRecords(exercise)).contains(child.id());
        when(sandbox.stopCommand(any())).thenReturn(StopTaskResponse.newBuilder().setHostEpoch("A".repeat(26)).setCancelledBeforeRegistration(true).build());
        coordinator.observe(childCommands.find(child.id()));
        assertThat(childCommands.beginPruning(child.id())).isFalse();
        commandTools.recoverTask(task.id()); execution.recover(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("STOPPED");
        assertThat(data.protectedCommandRecords(exercise)).isEmpty();
        verify(sandbox, times(1)).startCommand(any());
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void bashNonzeroExitWaitsForCopyCleanupBeforeResumingTheModel() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        var approved = commandTools.decide(SESSION, command.id(), CONTROL, "approve-exit", new CopilotProposalDecisionRequest("APPROVE", null));
        var child = childCommands.find(approved.commandTaskId());
        var coordinator = new CommandTaskCoordinator(childCommands, sandbox);
        when(sandbox.inventory(anyString(), anyString())).thenReturn(ExecutionInventory.newBuilder().setHostEpoch("A".repeat(26)).build());
        when(sandbox.startCommand(any())).thenReturn(bashSnapshot(child, false));
        coordinator.observe(child);
        commandTools.recoverTask(task.id());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_COMMAND");
        when(sandbox.command(any())).thenReturn(bashSnapshot(child, true));
        coordinator.observe(childCommands.find(child.id()));
        commandTools.recoverTask(task.id()); commandTools.recoverTask(task.id());
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
        var result = commandTools.read(SESSION, command.id()).result();
        assertThat(result.code()).isEqualTo("COMMAND_RESULT");
        assertThat(result.result().exitCode()).isEqualTo(7);
        assertThat(result.result().logs().getFirst().text()).isEqualTo("command log");
        verify(sandbox, times(1)).startCommand(any());
        verify(driver, times(2)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void bashSteerInvalidatesUnapprovedCommandWithoutTurningAStaleApprovalIntoExecution() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        steers.accept(SESSION, task.id(), CONTROL, "steer-command", new CopilotSteerRequest("Do not run that command"));
        var observed = commandTools.decide(SESSION, command.id(), CONTROL, "stale-approve", new CopilotProposalDecisionRequest("APPROVE", null));
        assertThat(observed.decision()).isNull();
        assertThat(observed.result().code()).isEqualTo("NOT_EXECUTED");
        assertThat(observed.result().reason()).isEqualTo("STEER_SUPERSEDED");
        assertThat(observed.commandTaskId()).isNull();
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void bashRevokedOriginalAuthorityCancelsItsChildWithoutResumingTheModel() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        var approved = commandTools.decide(SESSION, command.id(), CONTROL, "approve-revoke", new CopilotProposalDecisionRequest("APPROVE", null));
        invalidateOriginalAuthorizationUsingRealProxy();
        commandTools.recoverTask(task.id());
        assertThat(childCommands.find(approved.commandTaskId()).stopRequested()).isTrue();
        childCommands.cancelledBeforeRegistration(approved.commandTaskId());
        commandTools.recoverTask(task.id()); execution.recover(task.id());
        assertThat(repository.find(task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(repository.find(task.id()).reason()).isEqualTo("AUTHORIZATION_ENDED");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void bashStopCommittedBeforeApprovalCannotStartOrReviveTheParent() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        tasks.stop(SESSION, task.id(), "stop-before-approval");
        var result = commandTools.decide(SESSION, command.id(), CONTROL, "late-approval", new CopilotProposalDecisionRequest("APPROVE", null));
        assertThat(result.commandTaskId()).isNull();
        assertThat(result.result().code()).isEqualTo("TASK_STOPPED");
        execution.recover(task.id());
        assertThat(repository.find(task.id()).state()).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_command WHERE exercise_id=?::uuid", Integer.class, exercise)).isZero();
    }

    @Test
    void bashCompetingApprovalsBindOneExecutionAndDoNotAdoptTheLosersKey() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> raceBashApproval(command.id(), "first-decision", barrier));
            var second = workers.submit(() -> raceBashApproval(command.id(), "second-decision", barrier));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("WAITING", "COPILOT_PROPOSAL_DECIDED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_command WHERE exercise_id=?::uuid", Integer.class, exercise)).isEqualTo(1);
    }

    @Test
    void bashExpiredApprovalDoesNotCreateAnExecutionOrRestartTheModel() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        clock.now = clock.instant().plusSeconds(7200);
        commandTools.recoverTask(task.id()); execution.recover(task.id());
        assertThat(repository.find(task.id()).state()).isEqualTo("INTERRUPTED");
        assertThat(commandTools.read(SESSION, command.id()).result().code()).isEqualTo("EXERCISE_ENDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_command WHERE exercise_id=?::uuid", Integer.class, exercise)).isZero();
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void bashSteerWaitsForAnAcceptedCommandRatherThanCancellingOrReplayingIt() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        var accepted = commandTools.decide(SESSION, command.id(), CONTROL, "approve-before-steer", new CopilotProposalDecisionRequest("APPROVE", null));
        steers.accept(SESSION, task.id(), CONTROL, "steer-in-command", new CopilotSteerRequest("Explain the result before doing anything else"));
        commandTools.recoverTask(task.id());
        assertThat(repository.find(task.id()).state()).isEqualTo("WAITING_COMMAND");
        assertThat(childCommands.find(accepted.commandTaskId()).stopRequested()).isFalse();
        assertThat(steers.list(SESSION, task.id(), 0).items().getFirst().state()).isEqualTo("RECEIVED");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    @Test
    void bashSynchronizedCodeHistoryRetainsTheActualParentAndConversation() throws Exception {
        var task = beginBash();
        var command = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        var accepted = commandTools.decide(SESSION, command.id(), CONTROL, "approve-history", new CopilotProposalDecisionRequest("APPROVE", null));
        var child = childCommands.find(accepted.commandTaskId());
        assertThat(childCommands.claim(child.id(), "A".repeat(26))).isTrue();
        var snapshot = bashSnapshot(child, true).toBuilder().setSynchronization("SYNCED").setMutation(MutationReceipt.newBuilder()
            .setWorkspaceInstanceId(child.workspaceInstanceId()).setOperationId(child.id()).setRequestId(child.executionInstanceId())
            .setFingerprint("a".repeat(64)).setRevision(2).setChanged(true)).build();
        childCommands.observe(child.id(), snapshot, WorkspaceSnapshot.newBuilder().setWorkspaceInstanceId(child.workspaceInstanceId())
            .setRevision(2).addPaths("src/main/java/Main.java").build());
        var change = sql.getMapper(WorkspaceHistoryRepository.class).find(child.id());
        assertThat(change.source()).isEqualTo("BASH");
        assertThat(change.taskId()).isEqualTo(task.id());
        assertThat(change.conversationId()).isEqualTo(repository.find(task.id()).conversationId());
        assertThat(change.resultRevision()).isEqualTo(2);
        commandTools.recoverTask(task.id());
        assertThat(commandTools.read(SESSION, command.id()).state()).isEqualTo("RESOLVED");
    }

    private String raceBashApproval(String command, String key, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try { return commandTools.decide(SESSION, command, CONTROL, key, new CopilotProposalDecisionRequest("APPROVE", null)).state(); }
        catch (BusinessException rejected) { return rejected.errorCode().code(); }
    }

    private CommandSnapshot bashSnapshot(CommandTaskEntity task, boolean cleaned) {
        return CommandSnapshot.newBuilder().setSynchronization("UNCHANGED").setExecution(TestTaskSnapshot.newBuilder()
            .setExerciseId(task.exerciseId()).setTaskId(task.id()).setExecutionInstanceId(task.executionInstanceId())
            .setWorkspaceInstanceId(task.workspaceInstanceId()).setRevision(task.revision()).setTarget("bash").setState("EXITED")
            .setReason("command_exit").setExitCode(7).setQuiescent(true).setStartedAtUnixMillis(clock.instant().minusSeconds(1).toEpochMilli())
            .setFinishedAtUnixMillis(clock.instant().toEpochMilli()).setCleanup("SUCCEEDED").setCopyCleanup(cleaned ? "SUCCEEDED" : "PENDING")
            .setStateVersion(cleaned ? 3 : 2).setProblem(ProblemIdentity.newBuilder().setProblemId("fixture").setVersion("1")
                .setPackageSha256("a".repeat(64)).setExecutionImageDigest("sha256:" + "b".repeat(64)).setPlatform("linux/amd64"))
            .addLogs(TestLogChunk.newBuilder().setChannel("stdout").setData(ByteString.copyFromUtf8("command log")))).build();
    }

    @Test
    void bashMaximumCommandAndDirectoryReachApprovalThroughTheRunner() throws Exception {
        String command = "echo " + "x".repeat(4091);
        String directory = "d/".repeat(511) + "dd";
        var task = beginBash(command, directory);
        var proposal = commandTools.recent(SESSION, task.id(), 0).commands().getFirst();
        assertThat(proposal.command()).isEqualTo(command);
        assertThat(proposal.directory()).isEqualTo(directory);
        assertThat(proposal.commandTaskId()).isNull();
    }

    @Test
    void bashJsonEscapeExpansionDoesNotRejectALegalCommandBeforeApproval() throws Exception {
        String command = "echo " + "\n".repeat(4091);
        var task = beginBash(command, ".");
        assertThat(commandTools.recent(SESSION, task.id(), 0).commands().getFirst().command()).isEqualTo(command);
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
    }

    private CopilotTaskResponse beginBash() throws Exception { return beginBash("echo hello; exit 7", "."); }

    private CopilotTaskResponse beginBash(String command, String directory) throws Exception {
        var request = ToolExecutionRequest.builder().id("bash-first").name("bash")
            .arguments(json.writeValueAsString(Map.of("expected_revision", "1", "command", command, "directory", directory))).build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Command result understood.")).build(), 100));
        var task = tasks.accept(SESSION, exercise, CONTROL, "bash-start", "Inspect using Bash", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).as("bash registration reason: %s", tasks.find(SESSION, task.id()).reason()).isEqualTo("WAITING_APPROVAL");
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
        return beginProposal(workspace, trailingRead, false, ModelProtocol.OPENAI_CHAT);
    }

    private CopilotTaskResponse beginProposal(ProposalWorkspace workspace, boolean trailingRead, boolean thinking, ModelProtocol protocol) throws Exception {
        if (thinking) {
            var connection = connections.create(SESSION, "thinking-connection", new ModelConnectionRequest("Thinking fixture", "https://example.test", protocol, ModelProviderPreset.CUSTOM, "fixture-not-real", null));
            var model = models.create(SESSION, connection.id(), "thinking-model", new ModelConfigurationRequest("Thinking fixture", "fixture", 100000, 4096, ModelThinkingMode.ON, protocol == ModelProtocol.ANTHROPIC ? null : "low", protocol == ModelProtocol.ANTHROPIC ? 1024 : null, null, null));
            selection.select(SESSION, exercise, CONTROL, "thinking-select", new ModelInvocationReferenceDTO(connection.id(), model.id(), 1, 1));
        }
        var proposal = workspace.proposal();
        var read = ToolExecutionRequest.builder().id("after-proposal").name("view_file").arguments("{\"path\":\"" + ProposalWorkspace.PATH + "\"}").build();
        var prefix = ToolExecutionRequest.builder().id("before-proposal").name("view_file").arguments(read.arguments()).build();
        when(driver.call(any(), any(), any(), anyInt(), any())).thenReturn(
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.builder().thinking(thinking ? "opaque-fixture-thinking" : null).attributes(thinking ? protocolAttributes(protocol) : Map.of())
                .toolExecutionRequests(trailingRead ? List.of(prefix, proposal, read) : List.of(prefix, proposal)).build()).build(), 100),
            new ModelCallResultDTO(ChatResponse.builder().aiMessage(AiMessage.from("Finished.")).build(), 100));
        var task = tasks.accept(SESSION, exercise, CONTROL, "start", "Propose an edit", true, measurement);
        runner.run(task.id(), UUID.randomUUID().toString());
        assertThat(tasks.find(SESSION, task.id()).state()).isEqualTo("WAITING_APPROVAL");
        verify(driver, times(1)).call(any(), any(), any(), anyInt(), any());
        return task;
    }

    private Map<String, Object> protocolAttributes(ModelProtocol protocol) {
        return switch (protocol) {
            case OPENAI_CHAT -> Map.of();
            case OPENAI_RESPONSES -> Map.of("encrypted_reasoning", "fixture-encrypted");
            case ANTHROPIC -> Map.of("thinking_signature", "fixture-signature", "redacted_thinking", List.of("fixture-redacted"));
        };
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
