package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.TestHistoryRecord;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import ai.codecraft.modules.workspace.model.dto.TestProblemResponse;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.repository.DeletionRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceMaintenanceRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.TestEvidencePruningReceipt;
import ai.codecraft.workspace.v1.WorkspaceCollectionReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Public business modules with real migrations/MyBatis, not a REST/Go matrix. */
@EnabledIfEnvironmentVariable(named = "RETENTION_IT_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceRetentionPersistenceIT {
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private SqlSessionTemplate sql;
    private MutableClock clock;
    private ExerciseRepository exercises;
    private DeletionRepository deletions;
    private WorkspaceMaintenanceStore maintenance;
    private DeletionStore deletion;
    private TestTaskStore tasks;
    private String exercise;
    private String owner;
    private String workspace;
    private String prepare;

    @BeforeAll
    void migrateDisposableDatabase() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("RETENTION_IT_JDBC_URL"), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        for (Class<?> mapper : List.of(ExerciseRepository.class, DeletionRepository.class, WorkspaceMaintenanceRepository.class,
            WorkspaceWriteRepository.class, TestTaskRepository.class, TaskStreamRepository.class, FinalizationRepository.class)) {
            configuration.addMapper(mapper);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void seedOwnedExercise() {
        clock = new MutableClock();
        exercise = id(); owner = id(); workspace = id(); prepare = id();
        String problem = id();
        jdbc.update("INSERT INTO workspace_exercise(id,user_id,problem_version_id,workspace_instance_id,preparation_operation_id,state,revision,created_at,ended_at,retain_until) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'ENDED',30,?,?,?)",
            exercise, owner, problem, workspace, prepare, java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now.plusSeconds(30 * 86400)));
        operation(prepare, "PREPARE", "SUCCEEDED");
        jdbc.update("INSERT INTO workspace_control(exercise_id,gate_epoch,gate_state) VALUES (?::uuid,1,'FROZEN')", exercise);
        exercises = sql.getMapper(ExerciseRepository.class);
        deletions = sql.getMapper(DeletionRepository.class);
        var identity = mock(IdentityTransactionService.class);
        var authorization = new SessionAuthorizationDTO(owner, "USER", false, 1, clock.now.plusSeconds(60));
        when(identity.authorization("session")).thenReturn(authorization);
        when(identity.authorizeMutation("session")).thenReturn(authorization);
        when(identity.authorizePrivateDelivery("session")).thenReturn(authorization);
        var limits = new StorageProperties(512L * 1024 * 1024, 160L * 1024 * 1024, 20);
        maintenance = transactional(new WorkspaceMaintenanceStore(sql.getMapper(WorkspaceMaintenanceRepository.class), exercises, deletions, identity, clock, limits), WorkspaceMaintenanceStore.class);
        deletion = transactional(new DeletionStore(deletions, exercises, identity, clock, new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60)), DeletionStore.class);
        var problems = mock(ProblemRepository.class);
        when(problems.find(problem)).thenReturn(new ProblemVersionEntity(problem, "cart-total", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "{}"));
        tasks = transactional(new TestTaskStore(identity, mock(IdentitySecrets.class), exercises, sql.getMapper(WorkspaceWriteRepository.class), sql.getMapper(TestTaskRepository.class),
            problems, clock, json, sql.getMapper(TaskStreamRepository.class), mock(TaskStreamPublisher.class), sql.getMapper(FinalizationRepository.class), limits, maintenance), TestTaskStore.class);
    }

    @Test
    void historyRetiresOrdinaryRowsButPreservesFinalUnknownAndConflictReferences() throws Exception {
        var ordinary = new ArrayList<String>();
        for (int i = 0; i < 25; i++) ordinary.add(task(i, true, i));
        String finalTask = task(26, true, 26);
        jdbc.update("INSERT INTO workspace_final_task(task_id,finalization_id,freeze_operation_id,deadline_at) VALUES (?::uuid,?::uuid,?::uuid,?)",
            finalTask, id(), id(), java.sql.Timestamp.from(clock.now.plusSeconds(600)));
        task(27, false, 27);
        mutation(2, true);
        var history = tx(() -> tasks.recent("session", exercise));
        assertThat(history.tasks()).hasSize(22);
        assertThat(history.retention().prunedCount()).isEqualTo(5);
        assertThatThrownBy(() -> tx(() -> tasks.query("session", exercise, ordinary.getFirst())))
            .isInstanceOf(BusinessException.class).hasMessage("TEST_HISTORY_PRUNED");
        var sandbox = mock(SandboxWorkspaceGateway.class);
        when(sandbox.pruneTestEvidence(anyString(), any())).thenAnswer(call -> {
            TestHistoryRecord record = call.getArgument(1);
            if (record.taskId().equals(ordinary.getFirst())) throw new IllegalStateException("injected cleanup failure");
            return TestEvidencePruningReceipt.newBuilder().setTaskId(record.taskId()).setWorkspaceInstanceId(record.workspaceInstanceId())
                .setExecutionInstanceId(record.executionInstanceId()).setComplete(true).build();
        });
        when(sandbox.collectWorkspace(anyString(), anyLong(), anyList())).thenReturn(WorkspaceCollectionReceipt.newBuilder()
            .setWorkspaceInstanceId(workspace).setRevision(30).build());
        new WorkspaceMaintenanceCoordinator(maintenance, sandbox).collect(exercise);
        var plan = tx(() -> maintenance.plan(exercise));
        assertThat(plan.retainedRevisions()).contains(0L, 2L, 26L, 27L).doesNotContain(1L, 3L, 4L);
        assertThat(tx(() -> tasks.recent("session", exercise)).retention().cleanupPendingCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT snapshot->'logs' FROM workspace_test_task WHERE id = ?::uuid", String.class, ordinary.get(1))).isEqualTo("[]");
    }

    @Test
    void deletionErasesBodiesImmediatelyAndRetainsOutcomeReceiptsForSevenDays() throws Exception {
        String task = task(1, true, 1);
        String mutation = mutation(1, false);
        var coordination = new WorkspaceCoordinationStore(sql.getMapper(WorkspaceWriteRepository.class), exercises, json);
        tx(() -> deletion.request("session", exercise, "delete"));
        assertThatThrownBy(() -> tx(() -> tasks.query("session", exercise, task))).hasMessage("EXERCISE_DELETED");
        assertThat(tx(() -> deletion.reconciled(exercise))).isTrue();
        String claim = id();
        assertThat(tx(() -> deletion.claim(exercise, claim))).isTrue();
        tx(() -> { deletion.complete(exercise, claim); return null; });
        assertThat(tx(() -> coordination.mutation(mutation)).payload()).isEqualTo("{}");
        assertThat(tx(() -> coordination.mutation(mutation)).revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT snapshot->'logs' FROM workspace_test_task WHERE id = ?::uuid", String.class, task)).isEqualTo("[]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_test_task WHERE id = ?::uuid", Integer.class, task)).isEqualTo(1);
        clock.now = clock.now.plusSeconds(7 * 86400 - 1);
        tx(() -> { deletion.expireReceipts(); return null; });
        assertThat(tx(() -> coordination.mutation(mutation))).isNotNull();
        clock.now = clock.now.plusSeconds(1);
        tx(() -> { deletion.expireReceipts(); return null; });
        assertThat(tx(() -> coordination.mutation(mutation))).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_test_task WHERE id = ?::uuid", Integer.class, task)).isZero();
        assertThat(exercises.operation(prepare)).isNotNull();
        assertThat(exercises.deleted(exercise)).isTrue();
    }

    private String task(int revision, boolean quiescent, int seconds) throws Exception {
        String task = id();
        operation(task, "START_TEST", "SUCCEEDED");
        String execution = id();
        String snapshot = json.writeValueAsString(new TestTaskResponse(task, exercise, execution, workspace, Integer.toString(revision), "base", "STOPPED", "USER_STOPPED",
            clock.now.minusSeconds(120), clock.now.minusSeconds(60).plusSeconds(seconds), null, quiescent, quiescent ? "SUCCEEDED" : "FAILED", quiescent ? "SUCCEEDED" : "UNKNOWN", "0", false,
            List.of(new TestLogResponse("stdout", "private fixture log")), new TestProblemResponse("cart-total", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64"), null));
        jdbc.update("INSERT INTO workspace_test_task(id,execution_instance_id,exercise_id,workspace_instance_id,revision,gate_epoch,not_after,target,state,quiescent,snapshot) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,1,?,'base','STOPPED',?,?::jsonb)",
            task, execution, exercise, workspace, revision, java.sql.Timestamp.from(clock.now.plusSeconds(60)), quiescent, snapshot);
        return task;
    }

    private String mutation(int baseline, boolean conflict) {
        String operation = id();
        operation(operation, "FILE_BATCH", conflict ? "REJECTED" : "SUCCEEDED");
        jdbc.update("INSERT INTO workspace_mutation(operation_id,payload,gate_epoch,not_after,revision,changed,failure_code) VALUES (?::uuid,?,1,?,?,?,?)",
            operation, "{\"expectedRevision\":\"" + baseline + "\",\"changes\":[{\"content\":\"private code\"}]}",
            java.sql.Timestamp.from(clock.now.plusSeconds(60)), conflict ? null : baseline + 1, conflict ? null : true, conflict ? "REVISION_CONFLICT" : null);
        return operation;
    }

    private void operation(String operation, String kind, String state) {
        jdbc.update("INSERT INTO workspace_operation(id,user_id,exercise_id,workspace_instance_id,kind,target,request_key,fingerprint,state,created_at) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?)",
            operation, owner, exercise, workspace, kind, exercise, operation, "a".repeat(64), state, java.sql.Timestamp.from(clock.now));
    }

    private <T> T transactional(T target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions.getTransactionManager(), new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
