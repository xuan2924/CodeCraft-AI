package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import ai.codecraft.modules.workspace.repository.ExerciseReportRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class ReportDispatchFenceTest {
    @Test void staleOutboxGenerationCannotPrepareANewerUserRetryAfterOwnerLock() {
        var reports = mock(ExerciseReportRepository.class); var identity = mock(IdentityTransactionService.class);
        var store = new ExerciseReportStore(reports, mock(ExerciseRepository.class), null, identity, null, null, null, null, Clock.systemUTC(), null, null);
        var old = report("QUEUED", 1); var current = report("QUEUED", 2);
        when(reports.find("exercise")).thenReturn(old, current);
        assertThat(store.prepare("exercise", 1L, 0)).isNull();
        verify(identity).lockResourceOwner("owner");
        verify(reports, never()).prepare(any(), any(), any(), any());
    }
    @Test void recoveryScanCannotLaunchAQueuedRegenerationAfterItsSnapshotGoesStale() {
        var reports = mock(ExerciseReportRepository.class); var identity = mock(IdentityTransactionService.class);
        var store = new ExerciseReportStore(reports, mock(ExerciseRepository.class), null, identity, null, null, null, null, Clock.systemUTC(), null, null);
        var old = report("RUNNING", 1); var queued = report("QUEUED", 1);
        when(reports.find("exercise")).thenReturn(old, queued);
        store.expireRunning("exercise");
        verify(reports, never()).prepare(any(), any(), any(), any());
        verify(reports, never()).finishAttempt(any(), any(), any(), any(), any(), any());
    }
    private ExerciseReportEntity report(String state, long generation) {
        var report = mock(ExerciseReportEntity.class);
        when(report.userId()).thenReturn("owner"); when(report.state()).thenReturn(state); when(report.generation()).thenReturn(generation);
        return report;
    }
}
