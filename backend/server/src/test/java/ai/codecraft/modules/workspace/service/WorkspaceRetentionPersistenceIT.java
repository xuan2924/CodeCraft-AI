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
import ai.codecraft.modules.identity.service.IdentityRevocationNotifier;
import ai.codecraft.modules.identity.service.IdentityRevocationBarrier;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import ai.codecraft.modules.identity.repository.RevocationIntentRepository;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.workspace.config.FinalizationProperties;
import ai.codecraft.modules.workspace.model.dto.TestEvidenceResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.repository.StorageCapacityRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private ProblemRepository problems;
    private IdentityTransactionService identity;
    private StorageCapacityStore capacityStore;
    private String problemId;
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
            WorkspaceWriteRepository.class, TestTaskRepository.class, TaskStreamRepository.class, FinalizationRepository.class,
            IdentityRepository.class, StorageCapacityRepository.class)) {
            configuration.addMapper(mapper);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void seedOwnedExercise() {
        // The host execution slot is global, so unresolved fixture tasks cannot leak between cases.
        jdbc.execute("TRUNCATE workspace_test_stop, workspace_test_task CASCADE");
        clock = new MutableClock();
        exercise = id(); owner = id(); workspace = id(); prepare = id();
        String problem = id();
        problemId = problem;
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES (?::uuid,?,'fixture-only','USER',false)", owner, "u_" + owner.substring(0, 8));
        jdbc.update("INSERT INTO workspace_exercise(id,user_id,problem_version_id,workspace_instance_id,preparation_operation_id,state,revision,created_at,ended_at,retain_until) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'ENDED',30,?,?,?)",
            exercise, owner, problem, workspace, prepare, java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now.plusSeconds(30 * 86400)));
        operation(prepare, "PREPARE", "SUCCEEDED");
        jdbc.update("INSERT INTO workspace_control(exercise_id,gate_epoch,gate_state) VALUES (?::uuid,1,'FROZEN')", exercise);
        exercises = sql.getMapper(ExerciseRepository.class);
        deletions = sql.getMapper(DeletionRepository.class);
        identity = mock(IdentityTransactionService.class);
        var authorization = new SessionAuthorizationDTO(owner, "USER", false, 1, clock.now.plusSeconds(60));
        when(identity.authorization("session")).thenReturn(authorization);
        var ownerLocks = transactional(new IdentityTransactionService(sql.getMapper(IdentityRepository.class), clock,
            mock(IdentityRevocationNotifier.class), mock(RevocationIntentRepository.class), mock(IdentityRevocationBarrier.class)), IdentityTransactionService.class);
        org.mockito.Mockito.doAnswer(call -> { ownerLocks.lockResourceOwner(call.getArgument(0)); return null; }).when(identity).lockResourceOwner(anyString());
        when(identity.authorizeMutation("session")).thenAnswer(call -> { ownerLocks.lockResourceOwner(owner); return authorization; });
        when(identity.authorizePrivateDelivery("session")).thenAnswer(call -> { ownerLocks.lockResourceOwner(owner); return authorization; });
        var limits = new StorageProperties(512L * 1024 * 1024, 256L * 1024 * 1024, 20);
        capacityStore = transactional(new StorageCapacityStore(sql.getMapper(StorageCapacityRepository.class), exercises, sql.getMapper(WorkspaceWriteRepository.class), identity, limits, clock), StorageCapacityStore.class);
        maintenance = transactional(new WorkspaceMaintenanceStore(sql.getMapper(WorkspaceMaintenanceRepository.class), exercises, deletions, identity, clock, limits), WorkspaceMaintenanceStore.class);
        deletion = transactional(new DeletionStore(deletions, exercises, identity, clock, new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60)), DeletionStore.class);
        problems = mock(ProblemRepository.class);
        when(problems.find(problem)).thenReturn(new ProblemVersionEntity(problem, "cart-total", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "{}"));
        tasks = transactional(new TestTaskStore(identity, fingerprints(), exercises, sql.getMapper(WorkspaceWriteRepository.class), sql.getMapper(TestTaskRepository.class),
            problems, clock, json, sql.getMapper(TaskStreamRepository.class), mock(TaskStreamPublisher.class), sql.getMapper(FinalizationRepository.class), limits, maintenance, mock(StorageCapacityStore.class)), TestTaskStore.class);
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
    void ordinaryResultReusedByFinalizationIsNoLongerSubjectToOrdinaryWindow() throws Exception {
        String passed = task(30, true, 0, true);
        for (int i = 1; i <= 20; i++) task(30, true, i);
        String finalization = id();
        operation(finalization, "END", "PENDING");
        jdbc.update("UPDATE workspace_exercise SET state = 'FINAL_TESTING' WHERE id = ?::uuid", exercise);
        jdbc.update("INSERT INTO workspace_finalization(id,exercise_id,frozen_epoch,final_revision,started_at,deadline_at,max_attempts,budget_seconds,first_retry_seconds,second_retry_seconds) VALUES (?::uuid,?::uuid,1,30,?,?,3,600,5,30)",
            finalization, exercise, java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now.plusSeconds(600)));
        var finals = transactional(new FinalizationStore(sql.getMapper(FinalizationRepository.class), exercises, sql.getMapper(WorkspaceWriteRepository.class),
            sql.getMapper(TestTaskRepository.class), sql.getMapper(TaskStreamRepository.class), problems, identity, mock(IdentitySecrets.class), clock,
            new FinalizationProperties(3, 600, 5, 30), new FinalVerificationPolicy(clock), json, mock(StorageCapacityStore.class)), FinalizationStore.class);
        assertThat(finals.advance(exercise)).isNull();
        assertThat(finals.find(exercise).resultTaskId()).isEqualTo(passed);
        var history = tasks.recent("session", exercise);
        assertThat(history.tasks()).hasSize(21);
        assertThat(history.retention().prunedCount()).isZero();
        assertThat(tasks.query("session", exercise, passed).evidence()).isNotNull();
        assertThat(maintenance.pruningPlan(exercise)).isEmpty();
    }

    @Test
    void deletionErasesBodiesImmediatelyAndRetainsOutcomeReceiptsForSevenDays() throws Exception {
        String task = task(1, true, 1);
        String mutation = mutation(1, false);
        jdbc.update("UPDATE workspace_exercise SET selected_connection_id=?::uuid, selected_model_id=?::uuid, selected_connection_version=1, selected_model_version=1 WHERE id=?::uuid", id(), id(), exercise);
        var coordination = new WorkspaceCoordinationStore(sql.getMapper(WorkspaceWriteRepository.class), exercises, json);
        tx(() -> deletion.request("session", exercise, "delete"));
        assertThatThrownBy(() -> tx(() -> tasks.query("session", exercise, task))).hasMessage("EXERCISE_DELETED");
        assertThat(tx(() -> deletion.reconciled(exercise))).isTrue();
        String claim = id();
        assertThat(tx(() -> deletion.claim(exercise, claim))).isTrue();
        tx(() -> { deletion.complete(exercise, claim); return null; });
        assertThat(jdbc.queryForObject("SELECT selected_model_id IS NULL AND selected_connection_id IS NULL AND selected_model_version IS NULL AND selected_connection_version IS NULL FROM workspace_exercise WHERE id=?::uuid", Boolean.class, exercise)).isTrue();
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

    @Test
    void unconfirmedCopyCleanupRemainsInRecoveryEvenAfterExecutionQuiescence() throws Exception {
        String recovering = task(1, true, 1);
        jdbc.update("UPDATE workspace_test_task SET snapshot=jsonb_set(snapshot,'{copyCleanup}','\"UNKNOWN\"') WHERE id=?::uuid", recovering);
        assertThat(tasks.pending().stream().map(ai.codecraft.modules.workspace.model.entity.TestTaskEntity::id)).contains(recovering);
        assertThat(maintenance.pruningPlan(exercise)).isEmpty();
        assertThat(deletion.reconciled(exercise)).isFalse();
    }

    @Test
    void binaryLogProjectionDoesNotPreventAuthoritativeResultPersistenceInJsonb() throws Exception {
        String id = task(1, true, 1);
        jdbc.update("UPDATE workspace_test_task SET dispatched=true, host_epoch=? WHERE id=?::uuid", "A".repeat(26), id);
        var original = tasks.find(id);
        var expected = tasks.problem(original);
        var gateway = mock(ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway.class);
        var snapshot = ai.codecraft.workspace.v1.TestTaskSnapshot.newBuilder().setExerciseId(exercise).setTaskId(id).setExecutionInstanceId(original.executionInstanceId())
            .setWorkspaceInstanceId(workspace).setRevision(original.revision()).setTarget(original.target()).setState("STOPPED").setReason("USER_STOPPED")
            .setStartedAtUnixMillis(clock.instant().minusSeconds(1).toEpochMilli()).setFinishedAtUnixMillis(clock.instant().toEpochMilli())
            .setQuiescent(true).setCleanup("SUCCEEDED").setCopyCleanup("SUCCEEDED").setStateVersion(2).setStreamId("S".repeat(26))
            .setProblem(ai.codecraft.workspace.v1.ProblemIdentity.newBuilder().setProblemId(expected.problemId()).setVersion(expected.version())
                .setPackageSha256(expected.packageSha256()).setExecutionImageDigest(expected.executionImageDigest()).setPlatform(expected.platform()))
            .addLogs(ai.codecraft.workspace.v1.TestLogChunk.newBuilder().setChannel("stdout").setData(com.google.protobuf.ByteString.copyFrom(new byte[]{0, (byte) 255}))).build();
        when(gateway.task(original)).thenReturn(snapshot);
        new TestTaskCoordinator(tasks, gateway, mock(WorkspaceCoordinationStore.class), mock(WorkspaceWriteCoordinator.class)).observe(original);
        assertThat(jdbc.queryForObject("SELECT snapshot#>>'{logs,0,text}' FROM workspace_test_task WHERE id=?::uuid", String.class, id)).isEqualTo("\u2400\ufffd");
    }

    @Test
    void exhaustedOrdinaryBudgetStillAllowsOwnerRecoveryAfterLosingThePageCredential() throws Exception {
        String task = task(30, false, 1);
        jdbc.update("UPDATE workspace_exercise SET state='ACTIVE', ended_at=NULL, retain_until=NULL, started_at=?, deadline_at=? WHERE id=?::uuid",
            java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now.plusSeconds(2700)), exercise);
        jdbc.update("UPDATE workspace_control SET gate_state='OPEN', credential_hash=? WHERE exercise_id=?::uuid", IdentitySecrets.digest("lost-credential"), exercise);
        var full = new StorageMeasurement(exercise, "A".repeat(26), 1, 256L * 1024 * 1024);
        capacityStore.reconcile(capacityStore.plan(exercise), full);
        var writes = transactional(new WorkspaceWriteTransactionService(identity, fingerprints(), exercises, sql.getMapper(WorkspaceWriteRepository.class), clock, capacityStore), WorkspaceWriteTransactionService.class);
        assertThatThrownBy(() -> writes.control("session", exercise, "TAKEOVER", "blocked-takeover", null, "a".repeat(64), full))
            .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.code()).isEqualTo("STORAGE_BUDGET_EXCEEDED"));
        var stop = tasks.acceptRecoveryStop("session", exercise, task, "first-stop");
        assertThat(stop.ownerRecovery()).isTrue();
        assertThat(tasks.acceptRecoveryStop("session", exercise, task, "second-stop").id()).isEqualTo(stop.id());
        var finalization = transactional(new FinalizationStore(sql.getMapper(FinalizationRepository.class), exercises, sql.getMapper(WorkspaceWriteRepository.class),
            sql.getMapper(TestTaskRepository.class), sql.getMapper(TaskStreamRepository.class), problems, identity, fingerprints(), clock,
            new FinalizationProperties(3, 600, 5, 30), new FinalVerificationPolicy(clock), json, capacityStore), FinalizationStore.class);
        var end = finalization.recoveryEnd("session", exercise, "first-end");
        assertThat(finalization.recoveryEnd("session", exercise, "second-end").id()).isEqualTo(end.id());
        assertThat(exercises.find(exercise).state()).isEqualTo("CLOSING");
        assertThat(sql.getMapper(TestTaskRepository.class).pendingStops()).anyMatch(value -> value.id().equals(stop.id()) && value.ownerRecovery());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_operation WHERE exercise_id=?::uuid AND kind='STOP_TEST'", Integer.class, exercise)).isEqualTo(1);
    }

    @Test
    void databaseMeasurementIncludesOutOfLinePayloadValuesRatherThanOnlyToastPointers() {
        var first = capacityStore.reconcile(capacityStore.plan(exercise), new StorageMeasurement(exercise, "A".repeat(26), 1, 0));
        byte[] entropy = new byte[512 * 1024];
        new java.util.Random(1).nextBytes(entropy);
        String content = java.util.Base64.getEncoder().encodeToString(entropy);
        String operation = mutation(1, false);
        jdbc.update("UPDATE workspace_mutation SET payload=? WHERE operation_id=?::uuid", "{\"expectedRevision\":\"1\",\"changes\":[{\"content\":\"" + content + "\"}]}", operation);
        long storedValue = jdbc.queryForObject("SELECT pg_column_size(payload)::bigint FROM workspace_mutation WHERE operation_id=?::uuid", Long.class, operation);
        var after = capacityStore.reconcile(capacityStore.plan(exercise), new StorageMeasurement(exercise, "A".repeat(26), 2, 0));
        assertThat(storedValue).isGreaterThan(100000);
        assertThat(after.databaseBytes() - first.databaseBytes()).isGreaterThanOrEqualTo(storedValue);
    }

    @Test
    void acceptedDeletionReleasesHistorySlotBeforePhysicalCleanupUnderConcurrentCreation() throws Exception {
        for (int i = 1; i < 20; i++) seedEndedExercise();
        var creates = creationStore();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var deleting = pool.submit(() -> tx(() -> {
                identity.lockResourceOwner(owner);
                locked.countDown();
                await(release);
                return deletion.request("session", exercise, "delete-concurrently");
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var creating = pool.submit(() -> { entered.countDown(); return creates.create("session", problemId, "create-concurrently"); });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(creating.isDone()).isFalse();
                release.countDown();
                assertThat(deleting.get(5, TimeUnit.SECONDS).state()).isEqualTo("PENDING");
                assertThat(creating.get(5, TimeUnit.SECONDS).kind()).isEqualTo("PREPARE");
            } finally { release.countDown(); }
        }
        assertThat(exercises.retainedHistory(owner)).isEqualTo(19);
        assertThat(exercises.current(owner)).isNotNull();
    }

    @Test
    void twoConcurrentCreationsCannotConsumeTwoUnfinishedSlots() throws Exception {
        var creates = creationStore();
        var results = race(() -> creates.create("session", problemId, "first"), () -> creates.create("session", problemId, "second"));
        assertThat(results.stream().filter(WorkspaceOperationEntity.class::isInstance).count()).isEqualTo(1);
        assertThat(results).contains("ACTIVE_EXERCISE_EXISTS");
    }

    @Test
    void concurrentSpaceReservationsAndBusinessIntentRollbackAreAtomic() throws Exception {
        jdbc.update("UPDATE workspace_exercise SET state='ACTIVE', ended_at=NULL, retain_until=NULL, started_at=?, deadline_at=? WHERE id=?::uuid",
            java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now.plusSeconds(3600)), exercise);
        String credential = "A".repeat(43);
        jdbc.update("UPDATE workspace_control SET gate_state='OPEN', credential_hash=? WHERE exercise_id=?::uuid", IdentitySecrets.digest(credential), exercise);
        var first = new StorageMeasurement(exercise, "A".repeat(26), 1, 236L * 1024 * 1024);
        assertThat(capacityStore.reconcile(capacityStore.plan(exercise), first).databaseBytes()).isGreaterThan(0);
        var writes = transactional(new WorkspaceWriteTransactionService(identity, fingerprints(), exercises, sql.getMapper(WorkspaceWriteRepository.class), clock, capacityStore), WorkspaceWriteTransactionService.class);
        String payload = "{\"expectedRevision\":\"30\",\"changes\":[{\"path\":\"src/main/java/Main.java\",\"kind\":\"UPDATE\",\"content\":\"class Main {}\"}]}";
        var results = race(() -> writes.batch("session", exercise, "space-one", credential, payload, 30, first),
            () -> writes.batch("session", exercise, "space-two", credential, payload, 30, first));
        assertThat(results.stream().filter(WorkspaceOperationEntity.class::isInstance).count()).isEqualTo(1);
        assertThat(results).contains("STORAGE_BUDGET_EXCEEDED");
        var repository = sql.getMapper(StorageCapacityRepository.class);
        long reserved = repository.reservedBytes(exercise);
        assertThat(reserved).isEqualTo(StorageCapacityStore.MUTATION_OVERHEAD + payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        var afterCollection = new StorageMeasurement(exercise, first.meterEpoch(), 2, 216L * 1024 * 1024);
        capacityStore.reconcile(capacityStore.plan(exercise), afterCollection);
        assertThatThrownBy(() -> tx(() -> {
            writes.batch("session", exercise, "rollback", credential, payload, 30, afterCollection);
            throw new IllegalStateException("injected business rollback");
        })).hasMessage("injected business rollback");
        assertThat(repository.reservedBytes(exercise)).isEqualTo(reserved);
        assertThat(writes.existingBatch("session", exercise, "rollback", payload)).isNull();
    }

    private ExerciseTransactionService creationStore() {
        return transactional(new ExerciseTransactionService(exercises, identity, mock(ProblemTransactionService.class), clock, fingerprints(),
            mock(WorkspaceCoordinationStore.class), new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), capacityStore, mock(ExerciseModelService.class)), ExerciseTransactionService.class);
    }
    private IdentitySecrets fingerprints() {
        var secrets = mock(IdentitySecrets.class);
        when(secrets.fingerprint(anyString())).thenReturn("f".repeat(64));
        return secrets;
    }
    private void seedEndedExercise() {
        jdbc.update("INSERT INTO workspace_exercise(id,user_id,problem_version_id,workspace_instance_id,preparation_operation_id,state,created_at,ended_at,retain_until) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'ENDED',?,?,?)",
            id(), owner, problemId, id(), id(), java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now), java.sql.Timestamp.from(clock.now.plusSeconds(30 * 86400)));
    }
    private List<Object> race(Supplier<Object> first, Supplier<Object> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return outcome(first); });
            var right = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return outcome(second); });
            return List.of(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS));
        }
    }
    private Object outcome(Supplier<Object> action) {
        try { return action.get(); }
        catch (BusinessException rejected) { return rejected.code(); }
    }
    private void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture coordination timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private String task(int revision, boolean quiescent, int seconds) throws Exception { return task(revision, quiescent, seconds, false); }

    private String task(int revision, boolean quiescent, int seconds, boolean passed) throws Exception {
        String task = id();
        operation(task, "START_TEST", "SUCCEEDED");
        String execution = id();
        String state = passed ? "PASSED" : "STOPPED";
        String snapshot = json.writeValueAsString(new TestTaskResponse(task, exercise, execution, workspace, Integer.toString(revision), "base", state, passed ? "authority_report" : "USER_STOPPED",
            clock.now.minusSeconds(120), clock.now.minusSeconds(60).plusSeconds(seconds), passed ? 0 : null, quiescent, quiescent ? "SUCCEEDED" : "FAILED", quiescent ? "SUCCEEDED" : "UNKNOWN", "0", false,
            List.of(new TestLogResponse("stdout", "private fixture log")), new TestProblemResponse("cart-total", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64"), passed ? new TestEvidenceResponse("SUREFIRE_XML", "c".repeat(64), 100) : null));
        jdbc.update("INSERT INTO workspace_test_task(id,execution_instance_id,exercise_id,workspace_instance_id,revision,gate_epoch,not_after,target,state,quiescent,snapshot) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,1,?,'base',?,?,?::jsonb)",
            task, execution, exercise, workspace, revision, java.sql.Timestamp.from(clock.now.plusSeconds(60)), state, quiescent, snapshot);
        sql.getMapper(TaskStreamRepository.class).create(task, id());
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
