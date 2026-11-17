package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.copilot.repository.CopilotReportTraceRepository;
import ai.codecraft.modules.workspace.repository.InterviewerRepository;
import ai.codecraft.modules.workspace.repository.ReportTimelineRepository;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Isolated PostgreSQL mapper contract fixture; never uses the application's database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "COMMAND_IT_JDBC_URL", matches = ".+")
class ReportTimelinePersistenceIT {
    private JdbcTemplate bootstrap;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private String database;

    @BeforeAll
    void migrateIsolatedDatabase() throws Exception {
        String url = System.getenv("COMMAND_IT_JDBC_URL");
        int end = url.indexOf('?') < 0 ? url.length() : url.indexOf('?');
        int separator = url.lastIndexOf('/', end - 1);
        if (!url.startsWith("jdbc:postgresql://") || separator < "jdbc:postgresql://".length()) throw new IllegalArgumentException("PostgreSQL fixture required");
        database = "report_trace_it_" + UUID.randomUUID().toString().replace("-", "");
        bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, "fixture", "fixture"));
        bootstrap.execute("CREATE DATABASE " + database);
        var source = new DriverManagerDataSource(url.substring(0, separator + 1) + database + url.substring(end), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(InterviewerRepository.class, ReportTimelineRepository.class, CopilotReportTraceRepository.class)) configuration.addMapper(mapper);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @AfterAll
    void discardOnlyOwnedDatabase() {
        if (database != null && bootstrap != null) bootstrap.execute("DROP DATABASE IF EXISTS " + database);
    }

    @Test
    void successfulHintsHistoryAndTimelineAreDeterministicAndDoNotCountFailures() {
        String exercise = UUID.randomUUID().toString();
        for (int index = 0; index < 12; index++) {
            jdbc.update("""
                INSERT INTO workspace_interviewer_task(id,exercise_id,user_id,session_hash,control_hash,request_key,question,answer,state,
                  platform_version_id,phase,stage,mode,topic,hint_level,revision,created_at,deadline_at,completed_at)
                VALUES (?::uuid,?::uuid,?::uuid,'session','control',?,'question','answer',?,?::uuid,'ACTIVE','CHALLENGE','HINT',?, ?,7,
                  '2026-01-01T00:00:00Z'::timestamptz + ? * interval '1 second','2026-01-01T00:01:00Z','2026-01-01T00:00:30Z')
                """, UUID.randomUUID().toString(), exercise, UUID.randomUUID().toString(), "key" + index,
                index == 11 ? "FAILED" : "SUCCEEDED", UUID.randomUUID().toString(), index == 10 ? "other" : "rounding", index == 11 ? 3 : 2, index);
        }
        var interviewer = sql.getMapper(InterviewerRepository.class);
        assertThat(interviewer.successfulHints(exercise, "rounding")).isEqualTo(10);
        assertThat(interviewer.successfulHints(exercise, "other")).isEqualTo(1);
        assertThat(interviewer.completedHistory(exercise)).hasSize(8).allSatisfy(turn -> assertThat(turn.state()).isEqualTo("SUCCEEDED"));
        var repository = sql.getMapper(ReportTimelineRepository.class);
        assertThat(repository.hints(exercise)).hasSize(2).allSatisfy(hint -> assertThat(hint.maxLevel()).isEqualTo(2));
        var events = repository.timeline(exercise);
        assertThat(events).hasSize(12);
        assertThat(events).isSortedAccordingTo((left, right) -> left.timestamp().compareTo(right.timestamp()));
        assertThat(events.getFirst().revision()).isEqualTo("7");
        assertThat(repository.timeline(exercise)).isEqualTo(events);
        assertThat(repository.timeline(UUID.randomUUID().toString())).isEmpty();
        var trace = sql.getMapper(CopilotReportTraceRepository.class).trace(exercise);
        assertThat(trace.referenceCount()).isZero();
        assertThat(trace.skillLoadCount()).isZero();
        assertThat(trace.steerCount()).isZero();
        assertThat(trace.btwTaskCount()).isZero();
    }

    @Test
    void traceCountsTrustedReferencesSuccessfulLoadsAppliedSteersAndBtwAdmissions() {
        String exercise = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO copilot_task(id,exercise_id,user_id,session_hash,request_key,fingerprint,state,created_at,conversation_id,lane,reference_count)
            VALUES (?::uuid,?::uuid,?::uuid,'session','key','fingerprint','SUCCEEDED',now(),?::uuid,'BTW',2)
            """, task, exercise, owner, exercise);
        for (String outcome : List.of("OK", "DENIED", "LEGACY_UNCLASSIFIED")) {
            jdbc.update("""
                INSERT INTO copilot_tool(id,turn_id,task_id,exercise_id,position,call_id,name,arguments,state,created_at,outcome_code)
                VALUES (?::uuid,?::uuid,?::uuid,?::uuid,0,'call','load_skill','{}','SUCCEEDED',now(),?)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), task, exercise, outcome);
        }
        jdbc.update("""
            INSERT INTO copilot_skill_snapshot(task_id,exercise_id,user_id,skill_id,version_id,explicit_loaded_at)
            VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,now())
            """, task, exercise, owner, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        jdbc.update("""
            INSERT INTO copilot_steer(id,task_id,exercise_id,actor_id,actor_auth_version,request_key,fingerprint,text,message_payload,
              message_id,state,turn_id,created_at,completed_at)
            VALUES (?::uuid,?::uuid,?::uuid,?::uuid,1,'key','fingerprint','correct boundary','{}',?::uuid,'APPLIED',?::uuid,now(),now())
            """, UUID.randomUUID().toString(), task, exercise, owner, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var trace = sql.getMapper(CopilotReportTraceRepository.class).trace(exercise);
        assertThat(trace.referenceCount()).isEqualTo(2);
        assertThat(trace.skillLoadCount()).isEqualTo(2);
        assertThat(trace.steerCount()).isEqualTo(1);
        assertThat(trace.btwTaskCount()).isEqualTo(1);
    }
}
