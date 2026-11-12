package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Decisions use the policy snapshot and timestamps persisted for this finalization. */
@Service
public class FinalVerificationPolicy {
    private final Clock clock;

    public FinalVerificationPolicy(Clock clock) { this.clock = clock; }

    public boolean expired(FinalizationEntity budget) {
        return budget.deadlineAt() != null && !clock.instant().isBefore(budget.deadlineAt());
    }

    public boolean exhausted(FinalizationEntity budget) {
        return expired(budget) || budget.attempts() >= budget.maxAttempts();
    }

    public Instant retryAt(FinalizationEntity budget, Instant finishedAt) {
        return finishedAt.plusSeconds(budget.attempts() <= 1 ? budget.firstRetrySeconds() : budget.secondRetrySeconds());
    }
}
