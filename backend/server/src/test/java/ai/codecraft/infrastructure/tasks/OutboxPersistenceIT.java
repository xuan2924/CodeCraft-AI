package ai.codecraft.infrastructure.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.infrastructure.tasks.service.OutboxStore;
import ai.codecraft.modules.workspace.repository.SandboxNodeRepository;
import ai.codecraft.modules.workspace.service.SandboxRoutingStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
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
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "M3_IT_RUN", matches = "disposable")
class OutboxPersistenceIT {
    private JdbcTemplate bootstrap;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private TransactionTemplate transaction;
    private OutboxStore store;
    private SandboxNodeRepository nodes;
    private String database;
    @BeforeAll void initialize() throws Exception {
        String url = System.getenv("M3_IT_JDBC_URL");
        int end = url.indexOf('?') < 0 ? url.length() : url.indexOf('?');
        int slash = url.lastIndexOf('/', end - 1);
        database = "outbox_it_" + UUID.randomUUID().toString().replace("-", "");
        bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, "fixture", "fixture"));
        bootstrap.execute("CREATE DATABASE " + database);
        source = new DriverManagerDataSource(url.substring(0, slash + 1) + database + url.substring(end), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        var configuration = new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OutboxRepository.class); configuration.addMapper(SandboxNodeRepository.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var sql = new SqlSessionTemplate(factory.getObject());
        var manager = new DataSourceTransactionManager(source); transaction = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(new OutboxStore(sql.getMapper(OutboxRepository.class)));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        store = (OutboxStore) proxy.getProxy(); nodes = sql.getMapper(SandboxNodeRepository.class);
    }
    @BeforeEach void reset() {
        for (String table : List.of("outbox_consumption", "outbox_dead_letter", "outbox_events", "workspace_test_task", "workspace_report", "sandbox_binding", "sandbox_node")) jdbc.execute("DELETE FROM " + table);
    }
    @AfterAll void cleanup() { if (bootstrap != null && database != null) bootstrap.execute("DROP DATABASE IF EXISTS " + database); }
    private String enqueue() {
        String task = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO workspace_test_task(id,execution_instance_id,exercise_id,workspace_instance_id,revision,gate_epoch,not_after,target)
            VALUES(?::uuid,?::uuid,?::uuid,?::uuid,0,1,now()+interval '10 minutes','base')
            """, task, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT id::text FROM outbox_events WHERE aggregate_id=?::uuid", String.class, task);
    }
    @Test void triggerEnqueueRollsBackWithBusinessAndSkipLockedDoesNotBlock() throws Exception {
        assertThatThrownBy(() -> transaction.execute(status -> { enqueue(); throw new IllegalStateException("rollback"); })).hasMessage("rollback");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_test_task", Integer.class)).isZero();
        String locked = transaction.execute(status -> enqueue());
        String other = transaction.execute(status -> enqueue());
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM outbox_events WHERE id=?::uuid FOR UPDATE")) {
                statement.setString(1, locked); statement.executeQuery().close();
                var batch = store.claim("interactive");
                assertThat(batch).extracting(OutboxEvent::id).containsExactly(other);
            } finally { connection.rollback(); }
        }
        assertThat(store.claim("interactive")).extracting(OutboxEvent::id).containsExactly(locked);
    }
    @Test void lostConfirmLeaseFencingDedupBackoffAndDeadLetterArePersistent() {
        String id = enqueue(); var first = store.claim("interactive").getFirst();
        store.publishFailed(first);
        assertThat(store.claim("interactive")).isEmpty();
        jdbc.update("UPDATE outbox_events SET available_at=now()-interval '1 second' WHERE id=?::uuid", id);
        var replacement = store.claim("interactive").getFirst();
        store.published(first);
        assertThat(jdbc.queryForObject("SELECT state FROM outbox_events WHERE id=?::uuid", String.class, id)).isEqualTo("LEASED");
        store.published(replacement);
        for (int failure = 1; failure <= 5; failure++) {
            String token = UUID.randomUUID().toString(); var event = store.begin(id, token);
            assertThat(event).isNotNull();
            assertThat(store.begin(id, UUID.randomUUID().toString())).isNull();
            store.complete(event, UUID.randomUUID().toString()); // stale receiver cannot ACK business progress
            store.failed(event, token);
            if (failure < 5) {
                assertThat(store.begin(id, UUID.randomUUID().toString())).isNull();
                jdbc.update("UPDATE outbox_consumption SET lease_until=now()-interval '1 second' WHERE event_id=?::uuid", id);
            }
        }
        assertThat(jdbc.queryForObject("SELECT state FROM outbox_events WHERE id=?::uuid", String.class, id)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT failures FROM outbox_dead_letter WHERE event_id=?::uuid", Integer.class, id)).isEqualTo(5);
        assertThat(store.begin(id, UUID.randomUUID().toString())).isNull();
        assertThat(store.claim("interactive")).singleElement().extracting(OutboxEvent::state).isEqualTo("DEAD");
    }
    @Test void reportGenerationTriggerDeduplicatesAndDeadLetterNeverResetsPaidRunningState() {
        String exercise = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO workspace_report(exercise_id,id,user_id,session_hash,platform_version_id,final_revision,verification,
              review_skipped,review_text,operation_id,state,created_at,updated_at)
            VALUES(?::uuid,gen_random_uuid(),gen_random_uuid(),'session',gen_random_uuid(),0,'UNAVAILABLE',true,'',gen_random_uuid(),'QUEUED',now(),now())
            """, exercise);
        jdbc.update("UPDATE workspace_report SET state='QUEUED' WHERE exercise_id=?::uuid", exercise);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE workspace_report SET state='RUNNING' WHERE exercise_id=?::uuid", exercise);
        jdbc.update("UPDATE outbox_events SET state='DEAD' WHERE exercise_id=?::uuid", exercise);
        assertThat(jdbc.queryForObject("SELECT state FROM workspace_report WHERE exercise_id=?::uuid", String.class, exercise)).isEqualTo("RUNNING");
        jdbc.update("UPDATE workspace_report SET state='QUEUED',generation=2 WHERE exercise_id=?::uuid", exercise);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(2);
        jdbc.update("UPDATE outbox_events SET state='DEAD' WHERE exercise_id=?::uuid AND generation=1", exercise);
        assertThat(jdbc.queryForObject("SELECT state FROM workspace_report WHERE exercise_id=?::uuid", String.class, exercise)).isEqualTo("QUEUED");
        jdbc.update("UPDATE outbox_events SET state='DEAD' WHERE exercise_id=?::uuid AND generation=2", exercise);
        assertThat(jdbc.queryForObject("SELECT state FROM workspace_report WHERE exercise_id=?::uuid", String.class, exercise)).isEqualTo("FAILED");
    }
    @Test void committedCompletionSuppressesRedeliveryAndNodeVolumeCannotBeRebound() {
        String id = enqueue(); String token = UUID.randomUUID().toString();
        var event = store.begin(id, token); store.complete(event, token);
        assertThat(store.begin(id, UUID.randomUUID().toString())).isNull();
        nodes.register("default", "one:9090"); nodes.register("second", "two:9090");
        assertThat(nodes.healthy("default", "one:9090", "a".repeat(64), "A".repeat(26), 0, 4, true, "other")).isEqualTo(1);
        assertThat(nodes.healthy("second", "two:9090", "b".repeat(64), "B".repeat(26), 2, 4, true, "wanted")).isEqualTo(1);
        var routing = new SandboxRoutingStore(nodes, Clock.systemUTC());
        String exercise = UUID.randomUUID().toString();
        transaction.executeWithoutResult(status -> routing.bind(exercise, "wanted"));
        assertThat(nodes.bound(exercise)).isEqualTo("second");
        transaction.executeWithoutResult(status -> routing.admitExecution(exercise));
        assertThat(nodes.occupied("second")).isZero();
        assertThat(nodes.healthy("second", "two:9090", "c".repeat(64), "C".repeat(26), 0, 4, true, "wanted")).isZero();
        assertThat(nodes.healthy("second", "two:9090", "b".repeat(64), "B".repeat(26), 0, 4, false, "wanted")).isEqualTo(1);
        assertThat(routing.forExercise(exercise).ready()).isFalse(); // Recovery routes stay usable while dispatch is blocked.
        assertThatThrownBy(() -> routing.admitExecution(exercise)).hasMessage("WORKSPACE_UNAVAILABLE");
        nodes.unhealthy("second");
        transaction.executeWithoutResult(status -> routing.bind(exercise, "wanted"));
        assertThat(nodes.bound(exercise)).isEqualTo("second");
        assertThatThrownBy(() -> routing.forExercise(exercise)).hasMessage("WORKSPACE_UNAVAILABLE");
    }
}
