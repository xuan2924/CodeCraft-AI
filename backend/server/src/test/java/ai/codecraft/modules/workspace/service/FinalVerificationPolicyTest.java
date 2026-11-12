package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FinalVerificationPolicyTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void persistedDeadlineExpiresAtEqualityEvenAfterServiceRecreation() {
        FinalizationEntity frozen = budget(1);
        assertFalse(new FinalVerificationPolicy(Clock.fixed(START.plusSeconds(599), ZoneOffset.UTC)).expired(frozen));
        assertTrue(new FinalVerificationPolicy(Clock.fixed(START.plusSeconds(600), ZoneOffset.UTC)).expired(frozen));
        assertTrue(new FinalVerificationPolicy(Clock.fixed(START.plusSeconds(601), ZoneOffset.UTC)).expired(frozen));
    }

    @Test
    void retryDelayAndAttemptLimitComeFromPersistedBudget() {
        FinalVerificationPolicy policy = new FinalVerificationPolicy(Clock.fixed(START, ZoneOffset.UTC));
        assertEquals(START.plusSeconds(5), policy.retryAt(budget(1), START));
        assertEquals(START.plusSeconds(30), policy.retryAt(budget(2), START));
        assertTrue(policy.exhausted(budget(3)));
        assertFalse(policy.exhausted(budget(2)));
    }

    private static FinalizationEntity budget(int attempts) {
        return new FinalizationEntity("finalization", "exercise", 4L, 7L, START, START.plusSeconds(600),
            3, 600, 5, 30, attempts, "task", null, "PENDING", null, null, false);
    }
}
