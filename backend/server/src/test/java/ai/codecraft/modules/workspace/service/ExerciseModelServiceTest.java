package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationResolutionDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.service.ModelCheckService;
import ai.codecraft.modules.modelaccess.service.ModelInvocationService;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.repository.ExerciseModelRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ExerciseModelServiceTest {
    private final Instant now = Instant.parse("2026-06-01T00:00:00Z");
    private final ExerciseModelRepository selections = mock(ExerciseModelRepository.class);
    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final WorkspaceCoordinationStore control = mock(WorkspaceCoordinationStore.class);
    private final ModelInvocationService invocations = mock(ModelInvocationService.class);
    private final ModelCheckService checks = mock(ModelCheckService.class);
    private final ExerciseModelService service = new ExerciseModelService(selections, exercises, identity, control, invocations, checks, Clock.fixed(now, ZoneOffset.UTC), mock(IdentitySecrets.class));
    private final ModelInvocationReferenceDTO reference = new ModelInvocationReferenceDTO("10000000-0000-0000-0000-000000000001", "10000000-0000-0000-0000-000000000002", 1, 2);

    private void authorize(String state) {
        when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(3600)));
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "instance", "prepare", state, 1L, "[]", 1, now, now, now.plusSeconds(600), null, null, null));
    }

    @Test
    void selectionDoesNotStartPaidCheckAndAppliesOnlyExplicitRevision() {
        authorize("ACTIVE");
        assertThat(service.select("session", "exercise", "credential", "key", reference).selected()).isEqualTo(reference);
        verify(selections).select("exercise", reference);
        verify(checks, never()).start(anyString(), anyString(), anyString(), anyBoolean());
        verify(checks, never()).requireValid(anyString(), any());
    }

    @Test
    void unselectedCannotStartAndInvalidChecksCannotStart() {
        authorize("READY");
        assertThatThrownBy(() -> service.requireReady("session", "exercise")).isInstanceOf(BusinessException.class);
        when(selections.find("exercise")).thenReturn(reference);
        doThrow(new BusinessException(ModelAccessErrorCode.MODEL_CHECK_REQUIRED)).when(checks).requireValid("session", reference);
        assertThatThrownBy(() -> service.requireReady("session", "exercise")).isInstanceOf(BusinessException.class);
    }

    @Test
    void copilotRejectsSelectedButUncheckedVersion() {
        authorize("ACTIVE");
        when(selections.find("exercise")).thenReturn(reference);
        doThrow(new BusinessException(ModelAccessErrorCode.MODEL_CHECK_REQUIRED)).when(checks).requireValid("session", reference);
        assertThatThrownBy(() -> service.resolveForCall("session", "exercise")).isInstanceOfSatisfying(BusinessException.class,
            error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("MODEL_CHECK_REQUIRED"));
    }

    @Test
    void editedOrDeletedSelectionIsNotSilentlyUpdatedOrReplaced() {
        authorize("ACTIVE");
        when(selections.find("exercise")).thenReturn(reference);
        when(invocations.tryResolve("session", reference)).thenReturn(new ModelInvocationResolutionDTO(null, ModelAccessErrorCode.MODEL_VERSION_CONFLICT));
        assertThat(service.current("session", "exercise").availability()).isEqualTo("MODEL_VERSION_CONFLICT");
        when(invocations.tryResolve("session", reference)).thenReturn(new ModelInvocationResolutionDTO(null, ModelAccessErrorCode.MODEL_CONFIGURATION_NOT_FOUND));
        assertThat(service.current("session", "exercise").availability()).isEqualTo("MODEL_CONFIGURATION_NOT_FOUND");
        verify(selections, never()).select(anyString(), any());
    }

    @Test
    void endedExerciseCannotSwitch() {
        authorize("ENDED");
        assertThatThrownBy(() -> service.select("session", "exercise", "credential", "key", reference)).isInstanceOf(BusinessException.class);
        verify(selections, never()).select(anyString(), any());
    }
}
