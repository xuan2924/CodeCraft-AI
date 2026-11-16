package ai.codecraft.modules.platformagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.dto.PlatformDraftRequest;
import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.service.SkillPackageParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Upgrade boundary only; uses a disposable database and never calls a provider. */
@EnabledIfEnvironmentVariable(named = "COPILOT_IT_JDBC_URL", matches = ".+")
class ProviderPresetRetirementIT {
    @Test
    void migratesPersonalAndAllPlatformStatesWithoutLosingCredentialsOrBindings() {
        String url = System.getenv("COPILOT_IT_JDBC_URL");
        int end = url.indexOf('?') < 0 ? url.length() : url.indexOf('?');
        int separator = url.lastIndexOf('/', end - 1);
        String database = "preset_retirement_" + UUID.randomUUID().toString().replace("-", "");
        var bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, "fixture", "fixture"));
        bootstrap.execute("CREATE DATABASE " + database);
        try {
            var source = new DriverManagerDataSource(url.substring(0, separator + 1) + database + url.substring(end), "fixture", "fixture");
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("56").load().migrate();
            var definitions = new PlatformDefinitions(new ObjectMapper(), new ModelConnectionValidator(),
                new SkillPackageParser(new SkillProperties(50, 262144, 65536, 5242880, 32, 256, 4)), new PlatformPrompts());
            String owner = UUID.randomUUID().toString();
            for (String preset : List.of("GLM", "KIMI", "OPENAI", "DEEPSEEK")) {
                String id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO personal_model_connection(id,user_id,name,base_url,protocol,preset,credential_key_id,credential_nonce,credential_ciphertext,version,configuration_version,created_at,updated_at) "
                    + "VALUES(?::uuid,?::uuid,?,'https://fixture.invalid','OPENAI_CHAT',?,'key','nonce','ciphertext',1,1,now(),now())", id, owner, preset, preset);
                for (String state : List.of("DRAFT", "PUBLISHED", "RETIRED")) {
                    String version = UUID.randomUUID().toString();
                    String exercise = UUID.randomUUID().toString();
                    var input = new PlatformDraftRequest(PlatformRole.INTERVIEWER,
                        new ModelConnectionRequest("fixture", "https://fixture.invalid", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.DEEPSEEK, "fixture-key", null),
                        new ModelConfigurationRequest("fixture", "fixture", 32768, 4096, ModelThinkingMode.OFF, null, null, null, null), "Keep these instructions", List.of());
                    String definition = definitions.encode(definitions.prepare(version, input)).replace("\"DEEPSEEK\"", "\"" + preset + "\"");
                    jdbc.update("INSERT INTO platform_agent_version(id,role,definition,state,credential_key_id,credential_nonce,credential_ciphertext,created_at,published_at) "
                        + "VALUES(?::uuid,'INTERVIEWER',?::jsonb,?,'key','nonce','ciphertext',now(),now())", version, definition, state);
                    jdbc.update("INSERT INTO platform_exercise_binding VALUES(?::uuid,'INTERVIEWER',?::uuid)", exercise, version);
                    jdbc.update("INSERT INTO platform_agent_check(id,version_id,credential_revision,actor_id,session_hash,state,created_at,deadline_at,finished_at) "
                        + "VALUES(?::uuid,?::uuid,1,?::uuid,'fixture','SUCCEEDED',now(),now(),now())", UUID.randomUUID().toString(), version, owner);
                }
            }
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_connection WHERE preset='CUSTOM' AND version=2 AND configuration_version=2 AND credential_ciphertext='ciphertext'", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_connection WHERE preset='DEEPSEEK' AND configuration_version=1", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_exercise_binding", Integer.class)).isEqualTo(12);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_agent_version WHERE credential_ciphertext='ciphertext' AND revision=2 AND credential_revision=2", Integer.class)).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_agent_version v JOIN platform_agent_check c ON c.version_id=v.id WHERE c.credential_revision=v.credential_revision", Integer.class)).isEqualTo(3);
            for (String definition : jdbc.queryForList("SELECT definition::text FROM platform_agent_version", String.class)) {
                var decoded = definitions.decode(definition);
                assertThat(decoded.connection().preset()).isIn(ModelProviderPreset.CUSTOM, ModelProviderPreset.DEEPSEEK);
                assertThat(decoded.instructions()).isEqualTo("Keep these instructions");
                assertThat(decoded.connection().baseUrl()).isEqualTo("https://fixture.invalid");
            }
            assertThatThrownBy(() -> jdbc.update("UPDATE personal_model_connection SET preset='OPENAI'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            bootstrap.execute("DROP DATABASE " + database);
        }
    }
}
