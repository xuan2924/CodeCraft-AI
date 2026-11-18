package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.entity.ExerciseChallengeEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.repository.ExerciseChallengeRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExerciseChallengeStoreTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ExerciseChallengeRepository challenges = mock(ExerciseChallengeRepository.class);
    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final ProblemRepository problems = mock(ProblemRepository.class);
    private final ProblemPublicationReader publications = mock(ProblemPublicationReader.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final WorkspaceCoordinationStore coordination = mock(WorkspaceCoordinationStore.class);
    private final ExerciseDataService data = mock(ExerciseDataService.class);
    private final StorageCapacityStore capacity = mock(StorageCapacityStore.class);
    private final ExerciseChallengeStore store = new ExerciseChallengeStore(challenges, exercises, problems, publications, identity, coordination,
        data, capacity, mock(IdentitySecrets.class), Clock.fixed(now, ZoneOffset.UTC));
    private final ExerciseEntity exercise = new ExerciseEntity("exercise", "owner", "version", "instance", "prepare", "ACTIVE", 7L, "[]", 1,
        now.minusSeconds(60), now.minusSeconds(60), now.plusSeconds(60), null, null, null);

    @BeforeEach
    void setup() {
        var auth = new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(3600));
        when(identity.authorizeMutation("session")).thenReturn(auth);
        when(identity.authorizePrivateDelivery("session")).thenReturn(auth);
        when(exercises.find("exercise")).thenReturn(exercise);
        var problem = new ProblemVersionEntity("version", "cart", "1.1.0", "digest", "image", "linux/amd64", "{}");
        when(problems.challenge("version")).thenReturn(problem);
        when(problems.find("version")).thenReturn(problem);
        when(data.quiescent("exercise")).thenReturn(true);
    }

    @Test
    void oldOrMissingBaseEvidenceCannotEnterAndDoesNotRevealTheChallenge() {
        var state = store.find("session", "exercise");
        assertThat(state.available()).isTrue();
        assertThat(state.eligible()).isFalse();
        assertThat(state.publishedChallenge()).isNull();
        assertThatThrownBy(() -> store.enter("session", "exercise", "enter", "credential", null))
            .isInstanceOf(BusinessException.class).hasMessage("REVISION_CONFLICT");
        verify(publications, never()).challenge(any(), any());
        verify(challenges, never()).enter(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void enteringKeepsCodeAndDeadlineAndProtectsTheExactBaseEvidence() {
        when(challenges.currentBasePass("exercise")).thenReturn("base-test");
        doAnswer(call -> {
            when(challenges.find("exercise")).thenReturn(new ExerciseChallengeEntity("exercise", call.getArgument(1), 7, "base-test", now));
            return null;
        }).when(challenges).enter(eq("exercise"), anyString(), eq(7L), eq("base-test"), eq(now));
        var state = store.enter("session", "exercise", "enter", "credential", null);
        assertThat(state.entered()).isTrue();
        assertThat(state.enteredRevision()).isEqualTo("7");
        assertThat(state.baseTestId()).isEqualTo("base-test");
        assertThat(new ExerciseChallengeData(challenges).protectedTestRecords("exercise")).containsExactly("base-test");
        verify(exercises, never()).start(anyString(), any(), any());
        verify(publications).challenge(problems.find("version"), java.util.Set.of());
    }

    @Test
    void originalCommandCanBeObservedAfterPauseWithoutReentering() {
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "version", "instance", "prepare", "PAUSED", 7L, "[]", 2,
            now.minusSeconds(60), now.minusSeconds(60), null, null, null, null));
        when(exercises.receipt("owner", "ENTER_CHALLENGE", "exercise", "enter")).thenReturn(new WorkspaceOperationEntity("operation", "owner", "exercise", "instance",
            "ENTER_CHALLENGE", "exercise", "enter", "fingerprint", "SUCCEEDED", now));
        when(challenges.find("exercise")).thenReturn(new ExerciseChallengeEntity("exercise", "operation", 7, "base-test", now));
        assertThat(store.enter("session", "exercise", "enter", "stale", null).entered()).isTrue();
        verify(coordination, never()).checkControl(anyString(), anyString());
        verify(capacity, never()).admit(any(), any(), anyLong());
    }

    @Test
    void deadlineEqualityPreventsEntryEvenWithCurrentBasePass() {
        when(challenges.currentBasePass("exercise")).thenReturn("base-test");
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "version", "instance", "prepare", "ACTIVE", 7L, "[]", 1,
            now.minusSeconds(60), now.minusSeconds(60), now, null, null, null));
        assertThatThrownBy(() -> store.enter("session", "exercise", "enter", "credential", null))
            .isInstanceOf(BusinessException.class).hasMessage("INVALID_STATE");
        verify(challenges, never()).enter(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void inFlightModelsMustSettleBeforePublishingTheNextStage() {
        when(challenges.currentBasePass("exercise")).thenReturn("base-test");
        when(data.quiescent("exercise")).thenReturn(false);
        assertThatThrownBy(() -> store.enter("session", "exercise", "enter", "credential", null))
            .isInstanceOf(BusinessException.class).hasMessage("INVALID_STATE");
        verify(challenges, never()).enter(anyString(), anyString(), anyLong(), anyString(), any());
    }
}
