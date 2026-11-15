package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.workspace.model.dto.WorkspaceChangeContext;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceChangeEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceRestoreEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceHistoryRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkspaceHistoryLedgerTest {
    private final WorkspaceHistoryRepository history = mock(WorkspaceHistoryRepository.class);
    private final ExerciseDataService data = mock(ExerciseDataService.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final WorkspaceHistoryLedger ledger = new WorkspaceHistoryLedger(history, data, Clock.fixed(now, ZoneOffset.UTC));
    private final WorkspaceOperationEntity operation = new WorkspaceOperationEntity("operation", "owner", "exercise", "workspace",
        "FILE_BATCH", "exercise", "key", "fingerprint", "PENDING", now);

    @Test void manualAndPatchOriginsAreRecordedWithExactRevisionReferences() {
        ledger.mutation(operation, 2);
        var captured = ArgumentCaptor.forClass(WorkspaceChangeEntity.class);
        verify(history).insert(captured.capture());
        var manual = captured.getValue();
        assertThat(manual.source()).isEqualTo("MANUAL");
        assertThat(manual.baseRevision()).isEqualTo(1);
        assertThat(manual.resultRevision()).isEqualTo(2);
        assertThat(manual.retained()).isTrue();
        assertThat(manual.recordedAt()).isEqualTo(now);
        verify(history).retireExcess("exercise");
        reset(history);
        when(data.changeContext("exercise", "operation")).thenReturn(new WorkspaceChangeContext("task", "conversation"));
        ledger.mutation(operation, 2);
        verify(history).insert(captured.capture());
        assertThat(captured.getValue().source()).isEqualTo("AI_PATCH");
        assertThat(captured.getValue().taskId()).isEqualTo("task");
        assertThat(captured.getValue().conversationId()).isEqualTo("conversation");
    }

    @Test void bashKeepsItsSourceAndOptionalCopilotAssociation() {
        var task = mock(CommandTaskEntity.class);
        when(task.id()).thenReturn("command"); when(task.exerciseId()).thenReturn("exercise");
        when(task.workspaceInstanceId()).thenReturn("workspace"); when(task.revision()).thenReturn(2L);
        when(data.changeContext("exercise", "command")).thenReturn(new WorkspaceChangeContext("task", "conversation"));
        ledger.command(task, 3);
        var captured = ArgumentCaptor.forClass(WorkspaceChangeEntity.class);
        verify(history).insert(captured.capture());
        assertThat(captured.getValue().source()).isEqualTo("BASH");
        assertThat(captured.getValue().taskId()).isEqualTo("task");
        assertThatThrownBy(() -> ledger.command(task, 4)).isInstanceOf(IllegalStateException.class);
    }

    @Test void restorationIsANewChangeNotAnAiReplayAndRetiredHistoryCannotBeResurrected() {
        when(history.restore("operation")).thenReturn(new WorkspaceRestoreEntity("operation", "exercise", "original", "UNDO", "request", "preview", 1, null));
        ledger.mutation(operation, 2);
        var captured = ArgumentCaptor.forClass(WorkspaceChangeEntity.class);
        verify(history).insert(captured.capture());
        assertThat(captured.getValue().source()).isEqualTo("UNDO");
        assertThat(captured.getValue().originChangeId()).isEqualTo("original");
        assertThat(captured.getValue().taskId()).isNull();
        verifyNoInteractions(data);
        clearInvocations(history);
        when(history.find("operation")).thenReturn(new WorkspaceChangeEntity("operation", "exercise", "workspace", "UNDO", 1, 2, null, null, "original", false, now));
        ledger.mutation(operation, 2);
        verify(history, never()).insert(any());
        verify(history, never()).retireExcess(anyString());
        assertThatThrownBy(() -> ledger.mutation(operation, 3)).isInstanceOf(IllegalStateException.class);
    }

    @Test void noOpReceiptCannotExposeAnotherWritersUnindexedNewerSnapshot() {
        var writes = mock(WorkspaceWriteRepository.class);
        var exercises = mock(ExerciseRepository.class);
        var journal = mock(WorkspaceHistoryLedger.class);
        when(exercises.operation("operation")).thenReturn(operation);
        var store = new WorkspaceCoordinationStore(writes, exercises, new ObjectMapper(), journal);
        store.result(operation, MutationReceipt.newBuilder().setRevision(1).setChanged(false).build(),
            WorkspaceSnapshot.newBuilder().setRevision(2).addPaths("src/main/java/Later.java").build(), null);
        verify(writes, never()).observed(anyString(), anyLong(), anyString());
        verifyNoInteractions(journal);
        verify(writes).result("operation", 1L, false, null);
        verify(exercises).operationState("operation", "SUCCEEDED");
    }

    @Test void changedReceiptPinsHistoryBeforeExposingRevisionToCollection() {
        var writes = mock(WorkspaceWriteRepository.class);
        var exercises = mock(ExerciseRepository.class);
        var journal = mock(WorkspaceHistoryLedger.class);
        when(exercises.operation("operation")).thenReturn(operation);
        var store = new WorkspaceCoordinationStore(writes, exercises, new ObjectMapper(), journal);
        store.result(operation, MutationReceipt.newBuilder().setRevision(2).setChanged(true).build(),
            WorkspaceSnapshot.newBuilder().setRevision(2).build(), null);
        var order = inOrder(journal, writes);
        order.verify(writes).lock("exercise");
        order.verify(journal).mutation(operation, 2);
        order.verify(writes).observed("exercise", 2, "[]");
    }
}
