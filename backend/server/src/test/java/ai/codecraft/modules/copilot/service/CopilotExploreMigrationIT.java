package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "COPILOT_IT_JDBC_URL", matches = ".+")
class CopilotExploreMigrationIT {
    @Test void upgradesLegacyDelegationsAndBoundsExploreSlotsWithoutWeakeningTheWriterLane() {
        String url = System.getenv("COPILOT_IT_JDBC_URL");
        int end = url.indexOf('?') < 0 ? url.length() : url.indexOf('?');
        int separator = url.lastIndexOf('/', end - 1);
        if (!url.startsWith("jdbc:postgresql://") || separator < "jdbc:postgresql://".length()) throw new IllegalArgumentException("PostgreSQL fixture required");
        String database = "explore_it_" + id().replace("-", "");
        var bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, "fixture", "fixture"));
        bootstrap.execute("CREATE DATABASE " + database);
        try {
            var source = new DriverManagerDataSource(url.substring(0, separator + 1) + database + url.substring(end), "fixture", "fixture");
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("59").load().migrate();
            String exercise = id();
            String parent = id();
            String legacy = id();
            jdbc.update("INSERT INTO copilot_delegation(tool_id,exercise_id,parent_task_id,child_task_id,conversation_id,goal,state,created_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'legacy','WAITING',now())",
                legacy, exercise, parent, id(), id());
            var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("60").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT mode FROM copilot_delegation WHERE tool_id=?::uuid", String.class, legacy)).isEqualTo("EXECUTE");
            assertThatThrownBy(() -> delegation(jdbc, exercise, parent, "EXECUTE", null)).isInstanceOf(DataIntegrityViolationException.class);
            String exploreParent = id();
            for (int slot = 1; slot <= 3; slot++) delegation(jdbc, exercise, exploreParent, "EXPLORE", slot);
            assertThatThrownBy(() -> delegation(jdbc, exercise, exploreParent, "EXPLORE", 1)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> delegation(jdbc, exercise, exploreParent, "EXPLORE", 4)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> delegation(jdbc, exercise, exploreParent, "EXPLORE", null)).isInstanceOf(DataIntegrityViolationException.class);
            task(jdbc, exercise, "CHILD");
            assertThatThrownBy(() -> task(jdbc, exercise, "CHILD")).isInstanceOf(DataIntegrityViolationException.class);
            for (int index = 0; index < 3; index++) task(jdbc, exercise, "EXPLORE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_task WHERE lane='EXPLORE'", Integer.class)).isEqualTo(3);
        } finally { bootstrap.execute("DROP DATABASE IF EXISTS " + database); }
    }
    private void delegation(JdbcTemplate jdbc, String exercise, String parent, String mode, Integer slot) {
        jdbc.update("INSERT INTO copilot_delegation(tool_id,exercise_id,parent_task_id,child_task_id,conversation_id,goal,state,created_at,mode,explore_slot) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'goal','WAITING',now(),?,?)",
            id(), exercise, parent, id(), id(), mode, slot);
    }
    private void task(JdbcTemplate jdbc, String exercise, String lane) {
        jdbc.update("INSERT INTO copilot_task(id,exercise_id,user_id,session_hash,request_key,fingerprint,state,created_at,conversation_id,lane) VALUES(?::uuid,?::uuid,?::uuid,'fixture',?,'fixture','QUEUED',now(),?::uuid,?)",
            id(), exercise, id(), id(), id(), lane);
    }
    private String id() { return UUID.randomUUID().toString(); }
}
