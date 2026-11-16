package ai.codecraft.modules.platformagent.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.model.exception.PlatformAgentErrorCode;
import ai.codecraft.modules.platformagent.repository.PlatformCheckRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class PlatformCheckValidity {
    private final PlatformCheckRepository checks;
    private final Clock clock;
    private final PlatformAgentProperties policy;
    public PlatformCheckValidity(PlatformCheckRepository checks, Clock clock, PlatformAgentProperties policy) {
        this.checks = checks; this.clock = clock; this.policy = policy;
    }
    public void requireChecked(PlatformVersionEntity version, boolean fresh) {
        var check = checks.latest(version.id());
        if (check == null || !check.state().equals("SUCCEEDED") || check.credentialRevision() != version.credentialRevision()
            || fresh && !clock.instant().isBefore(check.finishedAt().plusSeconds(policy.checkValiditySeconds()))) {
            throw new BusinessException(PlatformAgentErrorCode.PLATFORM_CHECK_REQUIRED);
        }
    }
}
