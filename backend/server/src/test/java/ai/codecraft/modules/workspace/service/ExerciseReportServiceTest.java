package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.workspace.model.dto.ExerciseReportResponse;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExerciseReportServiceTest {
    private final ExerciseReportStore store = mock(ExerciseReportStore.class);
    private final ReportPdfRenderer renderer = mock(ReportPdfRenderer.class);
    private final ExerciseReportService service = new ExerciseReportService(store, mock(StorageCapacityService.class), renderer,
        new PlatformAgentProperties(1, 120, 300, 3, 300, 1800, 30));
    private ExerciseReportResponse snapshot(long generation, String state) {
        return new ExerciseReportResponse("exercise", "report", generation, state, "0", "UNAVAILABLE", null, true, "version", 1, null, null, List.of());
    }
    @Test
    void staleGenerationAndForeignOwnerCannotRenderPrivateBytes() throws Exception {
        when(store.view("session", "exercise")).thenReturn(snapshot(2, "UNKNOWN"));
        assertThatThrownBy(() -> service.pdf("session", "exercise", 1)).hasMessage("REVISION_CONFLICT");
        when(store.view("other", "exercise")).thenThrow(new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.pdf("other", "exercise", 2)).hasMessage("RESOURCE_NOT_FOUND");
        verify(renderer, never()).render(any());
    }
    @Test
    void deletionOrNewVisibleResultDuringRenderingPreventsDelivery() throws Exception {
        when(renderer.render(any())).thenReturn(new byte[] { 1 });
        when(store.view("session", "exercise")).thenReturn(snapshot(1, "RUNNING"), snapshot(1, "SUCCEEDED"));
        assertThatThrownBy(() -> service.pdf("session", "exercise", 1)).hasMessage("REVISION_CONFLICT");
        when(store.view("session", "exercise")).thenReturn(snapshot(1, "UNKNOWN")).thenThrow(new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED));
        assertThatThrownBy(() -> service.pdf("session", "exercise", 1)).hasMessage("EXERCISE_DELETED");
    }
    @Test
    void failedRenderReleasesCapacityAndIncompleteSnapshotRemainsExportable() throws Exception {
        when(store.view("session", "exercise")).thenReturn(snapshot(1, "UNKNOWN"));
        when(renderer.render(any())).thenThrow(new IOException("render failed")).thenReturn(new byte[] { 1, 2 });
        assertThatThrownBy(() -> service.pdf("session", "exercise", 1)).isInstanceOf(IOException.class);
        assertThat(service.pdf("session", "exercise", 1)).containsExactly(1, 2);
    }
}
