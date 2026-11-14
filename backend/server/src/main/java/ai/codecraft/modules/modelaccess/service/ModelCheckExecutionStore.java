package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.config.ModelCheckProperties;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckDispatchDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckRequestEntity;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short durable request admission; callers must invoke the SDK only after this transaction commits. */
@Service
public class ModelCheckExecutionStore {
    private final ModelCheckRepository checks;
    private final IdentityTransactionService identity;
    private final ModelInvocationService invocations;
    private final ModelCallProperties calls;
    private final ModelCheckProperties properties;
    private final Clock clock;

    public ModelCheckExecutionStore(ModelCheckRepository checks, IdentityTransactionService identity, ModelInvocationService invocations,
        ModelCallProperties calls, ModelCheckProperties properties, Clock clock) {
        this.checks = checks; this.identity = identity; this.invocations = invocations; this.calls = calls; this.properties = properties; this.clock = clock;
    }

    /** Internal worker hint only; begin rechecks the immutable reuse plan under the owner lock. */
    @Transactional(readOnly = true)
    public int firstOrdinal(String checkId) {
        var reuse = checks.reuse(checkId);
        return reuse != null && reuse.basicRequestId() != null ? 2 : 1;
    }

    @Transactional
    public ModelCheckDispatchDTO begin(String checkId, int ordinal, String workerId) {
        if (ordinal < 1 || ordinal > 3 || workerId == null) throw new IllegalArgumentException("Invalid check request identity");
        var initial = checks.find(checkId);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        var check = checks.find(checkId);
        if (check == null || !"QUEUED".equals(check.status()) && !"RUNNING".equals(check.status())) return null;
        var authority = checks.authority(checkId);
        var now = clock.instant();
        if (authority == null || !now.isBefore(authority.notAfter())) {
            checks.transition(checkId, "INTERRUPTED", "MODEL_CALL_CANCELLED"); return null;
        }
        var grant = identity.tryAuthorizeMutation(authority.sessionHash());
        if (grant == null || !grant.userId().equals(check.userId()) || !"USER".equals(grant.role()) || grant.authVersion() != authority.authVersion()) {
            checks.transition(checkId, "INTERRUPTED", "MODEL_CALL_CANCELLED"); return null;
        }
        var latest = checks.latest(checkId);
        if (latest != null && !now.isBefore(latest.notAfter())) {
            if ("DISPATCHED".equals(latest.status())) checks.completeRequest(latest.id(), "INTERRUPTED", 0, now, "MODEL_REQUEST_TIMEOUT");
            checks.transition(checkId, "INTERRUPTED", "MODEL_CALL_CANCELLED"); return null;
        }
        var reuse = checks.reuse(checkId);
        int first = reuse != null && reuse.basicRequestId() != null ? 2 : 1;
        if (latest == null ? ordinal != first : !"SUCCEEDED".equals(latest.status()) || ordinal != latest.ordinal() + 1) return null;
        if (authority.workerId() == null) {
            if (checks.claim(checkId, workerId) != 1) return null;
        } else if (!authority.workerId().equals(workerId)) return null;
        int remaining = properties.maxDecodedBytes() - checks.decodedBytes(checkId);
        if (remaining <= 0) { checks.transition(checkId, "FAILED", "MODEL_OUTPUT_LIMIT_EXCEEDED"); return null; }
        var reference = new ModelInvocationReferenceDTO(check.connectionId(), check.modelConfigurationId(), check.connectionConfigurationVersion(), check.modelConfigurationVersion());
        var resolution = invocations.tryResolve(authority.sessionHash(), reference);
        if (resolution.error() != null) {
            checks.transition(checkId, "FAILED", resolution.error().code()); return null;
        }
        var invocation = resolution.invocation();
        var deadline = now.plus(calls.timeout());
        if (authority.notAfter().isBefore(deadline)) deadline = authority.notAfter();
        if (grant.expiresAt().isBefore(deadline)) deadline = grant.expiresAt();
        var validatedAt = clock.instant();
        if (latest != null && !validatedAt.isBefore(latest.notAfter())) {
            checks.transition(checkId, "INTERRUPTED", "MODEL_CALL_CANCELLED"); return null;
        }
        if (!validatedAt.isBefore(deadline)) {
            checks.transition(checkId, "INTERRUPTED", "MODEL_REQUEST_TIMEOUT"); return null;
        }
        var request = new ModelCheckRequestEntity(UUID.randomUUID().toString(), checkId, ordinal, workerId, "DISPATCHED", now, deadline, 0);
        checks.insertRequest(request);
        checks.transition(checkId, "RUNNING", null);
        return new ModelCheckDispatchDTO(request.id(), checkId, ordinal, deadline, remaining, invocation);
    }

    /** Recovery never resends a request: expired/legacy authority and abandoned request deadlines become terminal. */
    @Transactional
    public boolean recoverExpired(String checkId) {
        var initial = checks.find(checkId);
        if (initial == null) return false;
        identity.lockResourceOwner(initial.userId());
        var check = checks.find(checkId);
        if (check == null || !"QUEUED".equals(check.status()) && !"RUNNING".equals(check.status())) return false;
        var authority = checks.authority(checkId);
        var latest = checks.latest(checkId);
        var now = clock.instant();
        if (authority != null && now.isBefore(authority.notAfter()) && (latest == null || now.isBefore(latest.notAfter()))) return false;
        if (latest != null && "DISPATCHED".equals(latest.status())) checks.completeRequest(latest.id(), "INTERRUPTED", 0, now, "MODEL_REQUEST_TIMEOUT");
        return checks.transition(checkId, "INTERRUPTED", "MODEL_CALL_CANCELLED") == 1;
    }

    @Transactional
    public boolean complete(String requestId, String workerId, int decodedBytes, ModelAccessErrorCode failure) {
        if (decodedBytes < 0) throw new IllegalArgumentException("Invalid decoded byte count");
        var initial = checks.request(requestId);
        if (initial == null) return false;
        var check = checks.find(initial.checkId());
        if (check == null) return false;
        identity.lockResourceOwner(check.userId());
        check = checks.find(initial.checkId());
        var request = checks.request(requestId);
        if (check == null || request == null || !"RUNNING".equals(check.status()) || !"DISPATCHED".equals(request.status()) || !request.workerId().equals(workerId)) return false;
        var now = clock.instant();
        if (!now.isBefore(request.notAfter())) {
            checks.completeRequest(requestId, "INTERRUPTED", 0, now, "MODEL_REQUEST_TIMEOUT");
            checks.transition(check.id(), "INTERRUPTED", "MODEL_REQUEST_TIMEOUT"); return false;
        }
        var authority = checks.authority(check.id());
        var grant = authority == null ? null : identity.tryAuthorizeMutation(authority.sessionHash());
        if (grant == null || !now.isBefore(authority.notAfter()) || !grant.userId().equals(check.userId())
            || !"USER".equals(grant.role()) || grant.authVersion() != authority.authVersion()) {
            checks.completeRequest(requestId, "INTERRUPTED", 0, now, "MODEL_CALL_CANCELLED");
            checks.transition(check.id(), "INTERRUPTED", "MODEL_CALL_CANCELLED"); return false;
        }
        var reference = new ModelInvocationReferenceDTO(check.connectionId(), check.modelConfigurationId(), check.connectionConfigurationVersion(), check.modelConfigurationVersion());
        var resolution = invocations.tryResolve(authority.sessionHash(), reference);
        if (resolution.error() != null) {
            checks.completeRequest(requestId, "INTERRUPTED", 0, now, resolution.error().code());
            checks.transition(check.id(), "SUPERSEDED", resolution.error().code()); return false;
        }
        String error = failure == null ? null : failure.code();
        int remaining = Math.max(0, properties.maxDecodedBytes() - checks.decodedBytes(check.id()));
        if (decodedBytes > remaining) { error = "MODEL_OUTPUT_LIMIT_EXCEEDED"; decodedBytes = remaining; }
        now = clock.instant();
        if (!now.isBefore(request.notAfter())) {
            checks.completeRequest(requestId, "INTERRUPTED", 0, now, "MODEL_REQUEST_TIMEOUT");
            checks.transition(check.id(), "INTERRUPTED", "MODEL_REQUEST_TIMEOUT"); return false;
        }
        if (checks.completeRequest(requestId, error == null ? "SUCCEEDED" : "FAILED", decodedBytes, now, error) != 1) return false;
        var reuse = checks.reuse(check.id());
        boolean finished = request.ordinal() == 3 || request.ordinal() == 1 && reuse != null && reuse.toolRequestId() != null;
        checks.transition(check.id(), error != null ? "FAILED" : finished ? "SUCCEEDED" : "RUNNING", error);
        return error == null;
    }
}
