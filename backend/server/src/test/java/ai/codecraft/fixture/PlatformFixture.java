package ai.codecraft.fixture;

import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.dto.PlatformDraftRequest;
import ai.codecraft.modules.platformagent.service.PlatformDefinitions;
import ai.codecraft.modules.platformagent.service.PlatformPrompts;
import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.service.SkillPackageParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Disposable legacy workspace/Copilot prerequisites only, not publication or provider evidence.
 * Never packaged in production. These synthetic versions are not used for platform model invocations.
 * The platform REST boundary suite instead creates, checks and publishes through actual services. */
@Component
@ConditionalOnExpression("'${WORKSPACE_IT_RUN:}' == 'disposable' or '${COPILOT_WORKSPACE_IT_RUN:}' == 'disposable' "
    + "or '${WORKSPACE_BROWSER_E2E:}' == 'disposable' or '${WORKSPACE_CRASH_IT_RUN:}' == 'disposable' "
    + "or '${COPILOT_BROWSER_E2E:}' == 'disposable' or '${COPILOT_RECOVERY_IT_RUN:}' == 'disposable'")
public class PlatformFixture {
    private final JdbcTemplate jdbc;
    public PlatformFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        var definitions = new PlatformDefinitions(new ObjectMapper(), new ModelConnectionValidator(),
            new SkillPackageParser(new SkillProperties(50, 262144, 65536, 5242880, 32, 256, 4)), new PlatformPrompts());
        var cipher = new ModelCredentialCipher(new ModelSecurityProperties("platform-fixture", "platform-fixture:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        for (PlatformRole role : PlatformRole.values()) {
            String id = role == PlatformRole.INTERVIEWER ? "00000000-0000-0000-0000-000000000041" : "00000000-0000-0000-0000-000000000042";
            var input = new PlatformDraftRequest(role,
                new ModelConnectionRequest("Fixture", "https://platform-fixture.invalid", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.CUSTOM, "fixture-not-real", null),
                new ModelConfigurationRequest("Fixture", "fixture", 32768, 4096, ModelThinkingMode.OFF, null, null, null, null),
                "Synthetic prerequisite for workspace tests, not a checked provider configuration.", List.of());
            var credential = cipher.encrypt("platform", id, "fixture-not-real");
            jdbc.update("INSERT INTO platform_agent_version(id,role,definition,state,credential_key_id,credential_nonce,credential_ciphertext,created_at,published_at) "
                + "VALUES(?::uuid,?,?::jsonb,'PUBLISHED',?,?,?,now(),now()) ON CONFLICT(id) DO NOTHING", id, role.name(),
                definitions.encode(definitions.prepare(id, input)), credential.keyId(), credential.nonce(), credential.ciphertext());
            jdbc.update("INSERT INTO platform_agent_check(id,version_id,credential_revision,actor_id,session_hash,state,created_at,deadline_at,finished_at) "
                + "VALUES(?::uuid,?::uuid,1,?::uuid,'fixture','SUCCEEDED',now(),now(),now()) ON CONFLICT(id) DO NOTHING", id, id, id);
            jdbc.update("UPDATE platform_agent_role SET current_version=?::uuid,revision=1 WHERE role=? AND current_version IS NULL", id, role.name());
        }
    }
}
