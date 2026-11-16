package ai.codecraft.modules.platformagent.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.service.ModelUsageAccounting;
import ai.codecraft.modules.platformagent.model.dto.PlatformCheckDispatchDTO;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.platformagent.repository.PlatformAgentRepository;
import ai.codecraft.modules.platformagent.repository.PlatformCheckRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformCheckStore {
    private final PlatformAgentRepository versions;
    private final PlatformCheckRepository checks;
    private final PlatformBindingService bindings;
    private final IdentityTransactionService identity;
    private final PlatformDefinitions definitions;
    private final ModelUsageAccounting usage;
    private final Clock clock;
    private final PlatformAgentProperties policy;

    public PlatformCheckStore(PlatformAgentRepository versions, PlatformCheckRepository checks, PlatformBindingService bindings,
        IdentityTransactionService identity, PlatformDefinitions definitions, ModelUsageAccounting usage, Clock clock, PlatformAgentProperties policy) {
        this.versions = versions; this.checks = checks; this.bindings = bindings; this.identity = identity;
        this.definitions = definitions; this.usage = usage; this.clock = clock; this.policy = policy;
    }

    @Transactional
    public List<String> discover() { checks.expire(clock.instant()); return checks.queued(); }

    @Transactional
    public PlatformCheckDispatchDTO claim(String id) {
        var check = checks.find(id);
        if (check == null || !check.state().equals("QUEUED")) return null;
        var auth = identity.tryAuthorizeMutation(check.sessionHash());
        versions.lockRoles();
        var version = versions.lock(check.versionId());
        if (auth == null || !auth.role().equals("ADMIN") || !auth.userId().equals(check.actorId()) || !clock.instant().isBefore(check.deadlineAt())) {
            checks.finish(id, "FAILED", clock.instant(), "PLATFORM_CHECK_INTERRUPTED", null); return null;
        }
        if (version == null || version.credentialCiphertext() == null || version.credentialRevision() != check.credentialRevision()
            || version.state().equals("RETIRED") && versions.bindings(version.id()) == 0) {
            checks.finish(id, "FAILED", clock.instant(), "PLATFORM_VERSION_CONFLICT", null); return null;
        }
        try {
            var target = bindings.invocation(version);
            var price = usage.price(target, clock.instant());
            var deadline = clock.instant().plusSeconds(policy.requestSeconds());
            if (checks.claim(id, clock.instant(), deadline, price) != 1) return null;
            return new PlatformCheckDispatchDTO(id, version.role(), target,
                definitions.instructions(version.role(), definitions.decode(version.definition())), price, deadline);
        } catch (BusinessException failure) {
            checks.finish(id, "FAILED", clock.instant(), failure.errorCode().code(), null); return null;
        }
    }

    @Transactional
    public void finish(String id, String error, String usageReceipt) {
        var check = checks.find(id);
        if (check == null || !check.state().equals("RUNNING")) return;
        var auth = identity.tryAuthorizeMutation(check.sessionHash());
        versions.lockRoles();
        var version = versions.lock(check.versionId());
        if (auth == null || !auth.role().equals("ADMIN") || !clock.instant().isBefore(check.deadlineAt())) error = "PLATFORM_CHECK_INTERRUPTED";
        if (version == null || version.credentialRevision() != check.credentialRevision()
            || version.state().equals("RETIRED") && versions.bindings(version.id()) == 0) error = "PLATFORM_VERSION_CONFLICT";
        checks.finish(id, error == null ? "SUCCEEDED" : "FAILED", clock.instant(), error, usageReceipt);
    }
}
