package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.InterviewerTaskEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.InterviewerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InterviewerStoreTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final InterviewerRepository tasks = mock(InterviewerRepository.class);
    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final WorkspaceCoordinationStore coordination = mock(WorkspaceCoordinationStore.class);
    private final PlatformBindingService platform = mock(PlatformBindingService.class);
    private final ExerciseModelService models = mock(ExerciseModelService.class);
    private final StorageCapacityStore capacity = mock(StorageCapacityStore.class);
    private final IdentitySecrets secrets = mock(IdentitySecrets.class);
    private final InterviewerStore store = new InterviewerStore(tasks, exercises, identity, coordination, platform, models, capacity, secrets, Clock.fixed(now, ZoneOffset.UTC));

    private void authorize(String state) {
        var auth = new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(3600));
        when(identity.authorizeMutation("session")).thenReturn(auth);
        when(identity.tryAuthorizeMutation("session")).thenReturn(auth);
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "instance", "prepare", state, 1L, "[]", 1, now, now, "ACTIVE".equals(state) ? now.plusSeconds(60) : null, null, null, null));
    }

    private InterviewerTaskEntity task(String state) {
        return new InterviewerTaskEntity("task", "exercise", "owner", "session", IdentitySecrets.digest("control"), "key", "What is required?", null, state, null, "version", "READY", null, now, now.plusSeconds(60), null, null);
    }

    @Test
    void clarificationDoesNotStartTheClockOrWriteCode() {
        authorize("READY");
        when(platform.version(anyString(), any())).thenThrow(new BusinessException(ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode.WORKSPACE_UNAVAILABLE));
        assertThatThrownBy(() -> store.accept("session", "exercise", "key", "control", "Question", null)).isInstanceOf(BusinessException.class);
        verify(models).requireReady("session", "exercise");
        verify(exercises, never()).start(anyString(), any(), any());
        verify(tasks, never()).insert(any());
    }

    @Test
    void anAcceptedRequestIsOnlyObservedOnRetryEvenAfterPause() {
        authorize("PAUSED");
        when(tasks.receipt("exercise", "key")).thenReturn(task("SUCCEEDED"));
        var result = store.accept("session", "exercise", "key", "new-control", "What is required?", null);
        assertThat(result.id()).isEqualTo("task");
        verify(platform, never()).version(anyString(), any());
        verify(tasks, never()).insert(any());
        verify(coordination, never()).checkControl(anyString(), anyString());
    }

    @Test
    void pausedExerciseCannotQueueANewPaidQuestion() {
        authorize("PAUSED");
        assertThatThrownBy(() -> store.accept("session", "exercise", "key", "control", "Question", null))
            .isInstanceOf(BusinessException.class).hasMessage("INVALID_STATE");
        verify(tasks, never()).insert(any());
    }

    @Test
    void anotherAccountCannotReadAnInterviewerTaskEvenWithItsIdentifier() {
        authorize("READY");
        when(identity.authorizePrivateDelivery("other")).thenReturn(new SessionAuthorizationDTO("other-owner", "USER", false, 1, now.plusSeconds(3600)));
        assertThatThrownBy(() -> store.find("other", "exercise", "task"))
            .isInstanceOf(BusinessException.class).hasMessage("RESOURCE_NOT_FOUND");
        verify(tasks, never()).find(anyString());
    }

    @Test
    void repeatedKeyCannotChangeTheOriginalQuestion() {
        authorize("READY");
        when(tasks.receipt("exercise", "key")).thenReturn(task("SUCCEEDED"));
        assertThatThrownBy(() -> store.accept("session", "exercise", "key", "control", "Different question", null))
            .isInstanceOf(BusinessException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        verify(tasks, never()).insert(any());
    }

    @Test
    void responseArrivingAfterPauseIsNotPublishedAsAnAnswer() {
        authorize("PAUSING");
        when(tasks.find("task")).thenReturn(task("RUNNING"));
        store.finish("task", "late answer", null, "usage");
        verify(tasks).finish("task", null, "INTERVIEWER_INTERRUPTED", now, "usage");
        verify(exercises).operationState("task", "REJECTED");
    }
}
