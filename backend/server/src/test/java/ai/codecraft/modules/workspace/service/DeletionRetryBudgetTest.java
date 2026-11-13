package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.workspace.model.entity.DeletionEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.repository.DeletionRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeletionRetryBudgetTest {
    @Test
    void manualRetryReceiptsAreBoundedWithoutPreventingAutomaticRecoveryOrSameKeyObservation() {
        var clock = new MutableClock();
        var repository = mock(DeletionRepository.class);
        var exercises = mock(ExerciseRepository.class);
        var identity = mock(IdentityTransactionService.class);
        var receipts = new HashMap<String, WorkspaceOperationEntity>();
        when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, clock.now.plusSeconds(3600)));
        when(repository.find("exercise")).thenReturn(new DeletionEntity("exercise", "owner", "workspace", "FAILED", clock.now, null, 1, clock.now, clock.now, "CLEANUP_FAILED"));
        when(exercises.receipt(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> receipts.get(call.getArgument(3)));
        doAnswer(call -> { WorkspaceOperationEntity receipt = call.getArgument(0); receipts.put(receipt.requestKey(), receipt); return null; }).when(exercises).insertOperation(any());
        Instant[] last = {null};
        when(repository.admitManualRetry(anyString(), any())).thenAnswer(call -> {
            if (last[0] != null && clock.now.isBefore(last[0].plusSeconds(60))) return 0;
            last[0] = clock.now; return 1;
        });
        var service = new DeletionStore(repository, exercises, identity, clock, new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60));
        service.retry("session", "exercise", "first");
        service.retry("session", "exercise", "first");
        assertEquals(1, receipts.size());
        assertEquals(429, assertThrows(BusinessException.class, () -> service.retry("session", "exercise", "another")).status());
        clock.now = clock.now.plusSeconds(60);
        service.retry("session", "exercise", "another");
        assertEquals(2, receipts.size());
    }

    @Test
    void expiredProgressCannotBeReadBackThroughMutationEndpointsButDeletionStillConfirmsRevocation() {
        var clock = new MutableClock();
        Instant completed = clock.now;
        clock.now = clock.now.plusSeconds(8 * 86400);
        var repository = mock(DeletionRepository.class);
        var exercises = mock(ExerciseRepository.class);
        var identity = mock(IdentityTransactionService.class);
        var grant = new SessionAuthorizationDTO("owner", "USER", false, 1, clock.now.plusSeconds(60));
        when(identity.authorization("session")).thenReturn(grant);
        when(identity.authorizeMutation("session")).thenReturn(grant);
        when(exercises.find("exercise")).thenReturn(new ai.codecraft.modules.workspace.model.entity.ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ENDED", 1L,
            "[]", 1, completed, null, null, null, null, null));
        when(repository.find("exercise")).thenReturn(new DeletionEntity("exercise", "owner", "workspace", "SUCCEEDED", completed, completed, 0, completed, null, null));
        var service = new DeletionStore(repository, exercises, identity, clock, new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60));
        assertEquals("RESOURCE_NOT_FOUND", assertThrows(BusinessException.class, () -> service.status("session", "exercise")).code());
        assertEquals("EXERCISE_DELETED", assertThrows(BusinessException.class, () -> service.request("session", "exercise", "delete")).code());
        assertEquals("EXERCISE_DELETED", assertThrows(BusinessException.class, () -> service.retry("session", "exercise", "retry")).code());
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
