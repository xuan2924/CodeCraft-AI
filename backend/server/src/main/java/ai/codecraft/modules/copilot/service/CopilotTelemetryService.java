package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotTelemetryResponse;
import ai.codecraft.modules.copilot.repository.CopilotTelemetryRepository;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotTelemetryService {
    private final CopilotTelemetryRepository ledger;
    private final ExerciseTransactionService exercises;

    public CopilotTelemetryService(CopilotTelemetryRepository ledger, ExerciseTransactionService exercises) {
        this.ledger = ledger;
        this.exercises = exercises;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CopilotTelemetryResponse exercise(String session, String exercise) {
        exercises.find(session, exercise);
        return new CopilotTelemetryResponse(ledger.exercise(exercise), ledger.recent(exercise));
    }

    public CopilotTelemetryResponse.Totals platform() {
        return ledger.platform();
    }
}
