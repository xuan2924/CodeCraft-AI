package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.CommandTaskRepository;
import ai.codecraft.modules.workspace.repository.DeletionRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.StorageCapacityRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.CommandSnapshot;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.ProblemIdentity;
import ai.codecraft.workspace.v1.TestTaskSnapshot;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "COMMAND_IT_JDBC_URL", matches = ".+")
class CommandPersistenceIT {
    private static final String SESSION = "a".repeat(64);
    private static final String HOST = "A".repeat(26);
    private static final String CREDENTIAL = "c".repeat(43);
    private static final CommandStartRequest INPUT = new CommandStartRequest("printf 'hello'", ".", "1");
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager transactions;
    private String database;
    private JdbcTemplate bootstrap;
    private String exercise;
    private String owner;
    private String workspace;
    private ProblemVersionEntity problem;
    private MutableClock clock;
    private IdentityTransactionService identity;
    private CommandTaskStore store;

    @BeforeAll void migrateIsolatedDatabase() throws Exception {
        String name = "command_it_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("COMMAND_IT_JDBC_URL");
        int end = url.indexOf('?') < 0 ? url.length() : url.indexOf('?');
        int separator = url.lastIndexOf('/', end - 1);
        if (!url.startsWith("jdbc:postgresql://") || separator < "jdbc:postgresql://".length()) throw new IllegalArgumentException("PostgreSQL fixture required");
        bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, "fixture", "fixture"));
        bootstrap.execute("CREATE DATABASE " + name);
        database = name;
        var source = new DriverManagerDataSource(url.substring(0, separator + 1) + database + url.substring(end), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        for (Class<?> mapper : List.of(CommandTaskRepository.class, ExerciseRepository.class, WorkspaceWriteRepository.class,
            TestTaskRepository.class, StorageCapacityRepository.class, DeletionRepository.class)) configuration.addMapper(mapper);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @AfterAll void discardOnlyOwnedDatabase() {
        if (database != null) bootstrap.execute("DROP DATABASE IF EXISTS " + database);
    }

    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE workspace_command,workspace_test_task");
        clock = new MutableClock();
        exercise = id(); owner = id(); workspace = id();
        problem = new ProblemVersionEntity(id(), "cart", "1.0.0", "b".repeat(64), "sha256:" + "c".repeat(64), "linux/amd64", "{}");
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES (?::uuid,?,'fixture','USER',false)", owner, "u_" + owner.substring(0, 8));
        jdbc.update("INSERT INTO workspace_exercise(id,user_id,problem_version_id,workspace_instance_id,preparation_operation_id,state,revision,created_at,started_at,deadline_at) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'ACTIVE',1,?,?,?)",
            exercise, owner, problem.id(), workspace, id(), Timestamp.from(clock.now), Timestamp.from(clock.now), Timestamp.from(clock.now.plusSeconds(2700)));
        jdbc.update("INSERT INTO workspace_control(exercise_id,credential_hash,gate_epoch,gate_state) VALUES (?::uuid,?,2,'OPEN')", exercise, IdentitySecrets.digest(CREDENTIAL));
        identity = mock(IdentityTransactionService.class);
        var grant = new SessionAuthorizationDTO(owner, "USER", false, 1, clock.now.plusSeconds(60));
        when(identity.authorizeMutation(SESSION)).thenReturn(grant);
        when(identity.authorizePrivateDelivery(SESSION)).thenReturn(grant);
        when(identity.tryAuthorizeMutation(SESSION)).thenReturn(grant);
        var secrets = mock(IdentitySecrets.class);
        when(secrets.fingerprint(anyString())).thenAnswer(call -> java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(((String) call.getArgument(0)).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var problems = mock(ProblemRepository.class);
        when(problems.find(problem.id())).thenReturn(problem);
        when(problems.executionAvailable(problem.id())).thenReturn(true);
        var json = JsonMapper.builder().addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var target = new CommandTaskStore(identity, secrets, sql.getMapper(ExerciseRepository.class), sql.getMapper(WorkspaceWriteRepository.class),
            sql.getMapper(TestTaskRepository.class), sql.getMapper(CommandTaskRepository.class), problems, mock(StorageCapacityStore.class), json, clock);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        store = (CommandTaskStore) proxy.getProxy();
    }

    @Test void receiptSurvivesChangedCredentialAndClosedGateWithoutASecondCommand() {
        var task = accept("same-key");
        jdbc.update("UPDATE workspace_control SET credential_hash=?,gate_state='CLOSED' WHERE exercise_id=?::uuid", IdentitySecrets.digest("d".repeat(43)), exercise);
        assertThat(store.previous(SESSION, exercise, "same-key", INPUT).id()).isEqualTo(task.id());
        assertThat(store.accept(SESSION, exercise, "same-key", "stale", INPUT, null).id()).isEqualTo(task.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_command", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.previous(SESSION, exercise, "same-key", new CommandStartRequest("different", ".", "1")))
            .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test void sharesAdmissionSlotAndOnlyDispatchesOnce() {
        var task = accept("first");
        assertThat(sql.getMapper(TestTaskRepository.class).occupied()).isEqualTo(1);
        assertThatThrownBy(() -> accept("second")).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(WorkspaceErrorCode.COMMAND_SLOT_BUSY));
        assertThat(store.claim(task.id(), HOST)).isTrue();
        assertThat(store.claim(task.id(), HOST)).isFalse();
        store.observe(task.id(), snapshot(task, "UNCHANGED"), null);
        assertThat(store.response(store.read(SESSION, exercise, task.id())).exitCode()).isEqualTo(7);
        assertThat(sql.getMapper(TestTaskRepository.class).occupied()).isZero();
        assertThat(sql.getMapper(ExerciseRepository.class).operation(task.id()).state()).isEqualTo("SUCCEEDED");
    }

    @Test void expiryQueuesSafetyStopWithoutWaitingOrDispatching() {
        var task = accept("expired");
        clock.now = task.notAfter();
        assertThat(store.claim(task.id(), HOST)).isFalse();
        assertThat(store.find(task.id()).stopRequested()).isTrue();
        assertThat(store.find(task.id()).dispatched()).isFalse();
        store.cancelledBeforeRegistration(task.id());
        assertThat(store.find(task.id()).quiescent()).isTrue();
        assertThat(store.find(task.id()).synchronization()).isEqualTo("UNCHANGED");
    }

    @Test void synchronizedRevisionAndOperationCommitTogether() {
        var task = accept("changed");
        store.claim(task.id(), HOST);
        var result = snapshot(task, "SYNCED").toBuilder().setMutation(MutationReceipt.newBuilder().setWorkspaceInstanceId(workspace)
            .setOperationId(task.id()).setRequestId(task.executionInstanceId()).setFingerprint("d".repeat(64)).setRevision(2).setChanged(true)).build();
        assertThatThrownBy(() -> store.observe(task.id(), result, null)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(task.id()).state()).isEqualTo("STARTING");
        store.observe(task.id(), result, WorkspaceSnapshot.newBuilder().setWorkspaceInstanceId(workspace).setRevision(2).addPaths("src/main/java/Main.java").build());
        assertThat(sql.getMapper(ExerciseRepository.class).find(exercise).revision()).isEqualTo(2);
        assertThat(store.response(store.read(SESSION, exercise, task.id())).synchronizedRevision()).isEqualTo("2");
    }

    @Test void retentionProtectsConflictsPaginatesAndRetiresUnknownPruningIdentity() {
        var protectedTask = accept("conflict");
        store.claim(protectedTask.id(), HOST);
        store.observe(protectedTask.id(), snapshot(protectedTask, "CONFLICT"), null);
        for (int i = 0; i < 22; i++) {
            clock.now = clock.now.plusSeconds(1);
            var task = accept("done-" + i);
            store.claim(task.id(), HOST);
            store.observe(task.id(), snapshot(task, "UNCHANGED"), null);
        }
        var history = store.recent(SESSION, exercise, null);
        assertThat(history.commands()).hasSize(20);
        assertThat(history.nextCursor()).isNotNull();
        assertThat(store.recent(SESSION, exercise, history.nextCursor()).commands()).hasSize(3);
        assertThat(store.pressureCandidates(exercise)).hasSize(4).noneMatch(task -> task.id().equals(protectedTask.id()));
        var candidates = store.evictionCandidates();
        assertThat(candidates).hasSize(2).noneMatch(task -> task.id().equals(protectedTask.id()));
        var retiring = candidates.getFirst();
        assertThat(store.beginPruning(retiring.id())).isTrue();
        assertThatThrownBy(() -> store.previous(SESSION, exercise, retiring.requestKey(), INPUT)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(WorkspaceErrorCode.COMMAND_HISTORY_PRUNED));
        assertThat(store.evictionCandidates()).anyMatch(task -> task.id().equals(retiring.id()));
        store.pruned(retiring.id());
        assertThat(sql.getMapper(TestTaskRepository.class).occupied()).isZero();
        assertThat(store.read(SESSION, exercise, protectedTask.id()).pruned()).isFalse();
        assertThat(sql.getMapper(StorageCapacityRepository.class).databaseBytes(exercise)).isPositive();
    }

    @Test void ownerAndControlAreRecheckedBeforeNewEffects() {
        var task = accept("owned");
        assertThatThrownBy(() -> store.stop(SESSION, exercise, task.id(), "wrong"))
            .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(WorkspaceErrorCode.STALE_CONTROL));
        when(identity.authorizePrivateDelivery(SESSION)).thenReturn(new SessionAuthorizationDTO(id(), "USER", false, 1, clock.now.plusSeconds(60)));
        assertThatThrownBy(() -> store.read(SESSION, exercise, task.id())).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(WorkspaceErrorCode.RESOURCE_NOT_FOUND));
        assertThat(store.find(task.id()).stopRequested()).isFalse();
    }

    private CommandTaskEntity accept(String key) { return store.accept(SESSION, exercise, key, CREDENTIAL, INPUT, null); }

    private CommandSnapshot snapshot(CommandTaskEntity task, String synchronization) {
        return CommandSnapshot.newBuilder().setSynchronization(synchronization).setExecution(TestTaskSnapshot.newBuilder()
            .setTaskId(task.id()).setExerciseId(exercise).setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(workspace)
            .setTarget("bash").setRevision(task.revision()).setState("EXITED").setExitCode(7).setStateVersion(1)
            .setStartedAtUnixMillis(clock.now.toEpochMilli()).setFinishedAtUnixMillis(clock.now.plusSeconds(1).toEpochMilli())
            .setQuiescent(true).setCleanup("SUCCEEDED").setCopyCleanup("SUCCEEDED")
            .setProblem(ProblemIdentity.newBuilder().setProblemId(problem.problemId()).setVersion(problem.version()).setPackageSha256(problem.packageSha256())
                .setExecutionImageDigest(problem.executionImageDigest()).setPlatform(problem.platform()))).build();
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
