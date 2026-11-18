package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.repository.CopilotTelemetryRepository;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import org.junit.jupiter.api.Test;

class CopilotTelemetryServiceTest {
    @Test void aForeignOrMissingExerciseCannotQueryItsLedger() {
        var exercises = mock(ExerciseTransactionService.class);
        var ledger = mock(CopilotTelemetryRepository.class);
        when(exercises.find("session", "foreign")).thenThrow(new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> new CopilotTelemetryService(ledger, exercises).exercise("session", "foreign"))
            .isInstanceOf(BusinessException.class).hasMessage("RESOURCE_NOT_FOUND");
        verifyNoInteractions(ledger);
    }
}
