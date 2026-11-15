package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.repository.CommandTaskRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandParentBoundaryTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final IdentitySecrets secrets = mock(IdentitySecrets.class);
    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final CommandTaskRepository commands = mock(CommandTaskRepository.class);
    private final ExerciseDataService data = mock(ExerciseDataService.class);
    private final CommandTaskStore store = new CommandTaskStore(identity, secrets, exercises, mock(WorkspaceWriteRepository.class),
        mock(TestTaskRepository.class), commands, mock(ProblemRepository.class), mock(StorageCapacityStore.class),
        JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC), data);

    @Test void backgroundAdmissionNeverAdoptsAManualReceiptEvenWithAnIdenticalKeyAndPayload() {
        when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60)));
        var exercise = mock(ExerciseEntity.class);
        when(exercise.userId()).thenReturn("owner");
        when(exercises.find("exercise")).thenReturn(exercise);
        when(secrets.fingerprint(anyString())).thenReturn("same-fingerprint");
        var manual = mock(CommandTaskEntity.class);
        when(manual.fingerprint()).thenReturn("same-fingerprint");
        when(commands.receipt("exercise", "copilot-command:tool")).thenReturn(manual);
        assertThatThrownBy(() -> store.acceptCopilot("session", "exercise", "copilot-command:tool", new CommandStartRequest("echo hi", ".", "1"), null))
            .isInstanceOf(BusinessException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        verify(commands, never()).insert(any());
    }

    @Test void parentEvidenceProtectionPrecedesPhysicalPruningIntent() {
        var child = mock(CommandTaskEntity.class);
        when(child.userId()).thenReturn("owner");
        when(child.exerciseId()).thenReturn("exercise");
        when(commands.find("child")).thenReturn(child);
        when(commands.lock("child")).thenReturn(child);
        when(data.protectedCommandRecords("exercise")).thenReturn(Set.of("child"));
        assertThat(store.beginPruning("child")).isFalse();
        verify(commands, never()).beginPruning(anyString(), any());
        verify(commands).checked("child", now);
    }

    @Test void boundCancellationCannotCrossExercises() {
        var child = mock(CommandTaskEntity.class);
        when(child.userId()).thenReturn("owner");
        when(child.exerciseId()).thenReturn("other-exercise");
        when(commands.find("child")).thenReturn(child);
        when(commands.lock("child")).thenReturn(child);
        assertThatThrownBy(() -> store.requestBoundStop("exercise", "child")).isInstanceOf(IllegalStateException.class);
        verify(commands, never()).stop(anyString());
    }
}
