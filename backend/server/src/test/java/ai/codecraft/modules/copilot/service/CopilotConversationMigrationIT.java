package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "COPILOT_IT_JDBC_URL", matches = ".+")
class CopilotConversationMigrationIT {
    @Test
    void existingHistoryAndPublishedSummaryAcquireOneInitialConversationWithoutDuplication() {
        String url = System.getenv("COPILOT_IT_JDBC_URL");
        int end = url.indexOf('?') < 0 ? url.length() : url.indexOf('?');
        int separator = url.lastIndexOf('/', end - 1);
        if (!url.startsWith("jdbc:postgresql://") || separator < "jdbc:postgresql://".length()) throw new IllegalArgumentException("PostgreSQL fixture required");
        String database = "conversation_it_" + UUID.randomUUID().toString().replace("-", "");
        var bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, "fixture", "fixture"));
        bootstrap.execute("CREATE DATABASE " + database);
        try {
            var source = new DriverManagerDataSource(url.substring(0, separator + 1) + database + url.substring(end), "fixture", "fixture");
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("38").load().migrate();
            String exercise = UUID.randomUUID().toString();
            String task = UUID.randomUUID().toString();
            String summary = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO workspace_exercise(id,user_id,problem_version_id,workspace_instance_id,preparation_operation_id,state,created_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'ENDED',now())",
                exercise, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
            jdbc.update("INSERT INTO copilot_task(id,exercise_id,user_id,session_hash,request_key,fingerprint,state,created_at,completed_at) VALUES(?::uuid,?::uuid,?::uuid,'session','old','fingerprint','SUCCEEDED',now(),now())",
                task, exercise, UUID.randomUUID().toString());
            jdbc.update("INSERT INTO copilot_message(id,task_id,exercise_id,kind,payload,created_at) VALUES(?::uuid,?::uuid,?::uuid,'USER','original evidence',now())",
                UUID.randomUUID().toString(), task, exercise);
            jdbc.update("INSERT INTO copilot_compaction(id,exercise_id,user_id,session_hash,actor_auth_version,request_key,fingerprint,trigger_kind,state,source_version,through_sequence,summary_payload,created_at,completed_at) VALUES(?::uuid,?::uuid,?::uuid,'session',1,'old','fingerprint','MANUAL','APPLIED',0,1,'retained summary',now(),now())",
                summary, exercise, UUID.randomUUID().toString());
            jdbc.update("INSERT INTO copilot_context(exercise_id,version,summary_id,through_sequence,updated_at) VALUES(?::uuid,1,?::uuid,1,now())", exercise, summary);
            var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
            flyway.migrate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT id::text FROM copilot_conversation", String.class)).isEqualTo(exercise);
            assertThat(jdbc.queryForObject("SELECT conversation_id::text FROM copilot_task", String.class)).isEqualTo(exercise);
            assertThat(jdbc.queryForObject("SELECT conversation_id::text FROM copilot_compaction", String.class)).isEqualTo(exercise);
            assertThat(jdbc.queryForObject("SELECT summary_id::text FROM copilot_context WHERE conversation_id=?::uuid", String.class, exercise)).isEqualTo(summary);
            assertThat(jdbc.queryForObject("SELECT payload FROM copilot_message WHERE task_id=?::uuid", String.class, task)).isEqualTo("original evidence");
            assertThat(jdbc.queryForObject("SELECT summary_payload FROM copilot_compaction", String.class)).isEqualTo("retained summary");
        } finally {
            bootstrap.execute("DROP DATABASE IF EXISTS " + database);
        }
    }
}
