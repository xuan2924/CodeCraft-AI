package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import ai.codecraft.modules.workspace.model.dto.EvaluationReportDTO;
import ai.codecraft.modules.workspace.model.dto.ReportReviewRequest;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.repository.ExerciseReportRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExerciseReportStoreTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ExerciseReportRepository reports = mock(ExerciseReportRepository.class);
    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final FinalizationRepository finals = mock(FinalizationRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final PlatformBindingService platform = mock(PlatformBindingService.class);
    private final StorageCapacityStore capacity = mock(StorageCapacityStore.class);
    private final IdentitySecrets secrets = mock(IdentitySecrets.class);
    private final ExerciseReportStore store = new ExerciseReportStore(reports, exercises, finals, identity, platform, capacity, secrets, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void authorize() {
        var auth = new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(3600));
        when(identity.authorizeMutation("session")).thenReturn(auth);
        when(identity.authorizePrivateDelivery("session")).thenReturn(auth);
        when(identity.tryAuthorizeMutation("session")).thenReturn(auth);
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "instance", "prepare", "ENDED", 7L, "[]", 1, now, now, null, null, null, null));
        var frozen = mock(FinalizationEntity.class);
        when(frozen.finalRevision()).thenReturn(7L);
        when(frozen.endedAt()).thenReturn(now);
        when(frozen.verification()).thenReturn("PASSED");
        when(finals.find("exercise")).thenReturn(frozen);
        var version = mock(PlatformVersionEntity.class);
        when(version.id()).thenReturn("version");
        when(platform.version("exercise", PlatformRole.EVALUATOR)).thenReturn(version);
    }
    private ExerciseReportEntity report(String state, int attempts, long generation, Instant deadline) {
        return new ExerciseReportEntity("exercise", "report", "owner", "session", "version", 7, "PASSED", "test", true, "", "operation",
            generation, state, attempts, "attempt", deadline, "[]", null, null, now, now);
    }
    @Test
    void getDoesNotCreateOrChargeForAReport() {
        assertThat(store.view("session", "exercise").state()).isEqualTo("WAITING_REVIEW");
        verify(reports, never()).insert(any());
        verify(capacity, never()).admit(any(), any(), anyLong());
    }
    @Test
    void reviewRequiresConfirmedFrozenRevisionAndRejectsInvalidSkipOrOversizedText() {
        when(finals.find("exercise")).thenReturn(null);
        assertThatThrownBy(() -> store.review("session", "exercise", "key", new ReportReviewRequest(true, ""), null)).hasMessage("INVALID_STATE");
        for (var input : List.of(new ReportReviewRequest(true, "not empty"), new ReportReviewRequest(false, " "), new ReportReviewRequest(false, "汉".repeat(1366)))) {
            assertThatThrownBy(() -> store.reviewFingerprint(input)).isInstanceOf(BusinessException.class).hasMessage("INVALID_REQUEST");
        }
        verify(reports, never()).insert(any());
    }
    @Test
    void crossAccountAndDeletedEvidenceIsNotReadable() {
        when(identity.authorizePrivateDelivery("other")).thenReturn(new SessionAuthorizationDTO("other-owner", "USER", false, 1, now.plusSeconds(3600)));
        assertThatThrownBy(() -> store.view("other", "exercise")).hasMessage("RESOURCE_NOT_FOUND");
        when(exercises.deleted("exercise")).thenReturn(true);
        assertThatThrownBy(() -> store.evidence("session", "exercise", 1, "file:7:Main.java")).hasMessage("EXERCISE_DELETED");
        verify(reports, never()).find(anyString());
    }
    @Test
    void expiredDispatchedCallBecomesUnknownAndIsNeverAutomaticallyRedispatched() {
        when(reports.find("exercise")).thenReturn(report("RUNNING", 1, 1, now));
        assertThat(store.prepare("exercise")).isNull();
        verify(reports).finishAttempt("attempt", "UNKNOWN", now, null, null, "EVALUATION_RESULT_UNKNOWN");
        verify(reports).finish("exercise", 1, "UNKNOWN", null, "EVALUATION_RESULT_UNKNOWN", now);
        verify(reports, never()).prepare(anyString(), anyString(), any(), any());
    }
    @Test
    void invalidCompletedResultMayRegenerateButThirdAttemptTerminates() {
        var first = report("RUNNING", 1, 1, now.plusSeconds(10));
        when(reports.find("exercise")).thenReturn(first);
        store.complete(first, null, "bad json", "usage", "EVALUATION_INVALID_RESPONSE", true);
        verify(reports).finish("exercise", 1, "QUEUED", null, "EVALUATION_INVALID_RESPONSE", now);
        var last = report("RUNNING", 3, 1, now.plusSeconds(10));
        when(reports.find("exercise")).thenReturn(last);
        store.complete(last, null, "bad json", "usage", "EVALUATION_INVALID_RESPONSE", true);
        verify(reports).finish("exercise", 1, "FAILED", null, "EVALUATION_INVALID_RESPONSE", now);
        verify(exercises).operationState("operation", "REJECTED");
    }
    @Test
    void deletedOrRevokedOwnerCannotPublishLateEvaluation() {
        var ticket = report("RUNNING", 1, 1, now.plusSeconds(10));
        when(reports.find("exercise")).thenReturn(ticket);
        when(identity.tryAuthorizeMutation("session")).thenReturn(null);
        store.complete(ticket, new EvaluationReportDTO("PASSED", null, "late", List.of()), "private result", "usage", null, false);
        verify(reports).finish("exercise", 1, "FAILED", null, "EVALUATION_AUTHORIZATION_CHANGED", now);
    }
    @Test
    void lateOldGenerationCannotModifyNewGenerationOrItsUsage() {
        var old = report("RUNNING", 1, 1, now.plusSeconds(10));
        when(reports.find("exercise")).thenReturn(report("RUNNING", 1, 2, now.plusSeconds(10)));
        store.complete(old, null, "old", "usage", "EVALUATION_INVALID_RESPONSE", true);
        verify(reports, never()).finish(anyString(), anyLong(), anyString(), any(), any(), any());
        verify(reports, never()).finishAttempt(anyString(), anyString(), any(), any(), any(), any());
    }
    @Test
    void evidenceLookupRequiresTheExactGeneration() {
        when(reports.find("exercise")).thenReturn(report("SUCCEEDED", 1, 2, null));
        assertThatThrownBy(() -> store.evidence("session", "exercise", 1, "file:7:Main.java")).hasMessage("REVISION_CONFLICT");
    }
}
