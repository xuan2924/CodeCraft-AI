package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotUsageContextResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotUsageResponse;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.repository.CopilotUsageRepository;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotUsageService {
    private final CopilotUsageRepository usage;
    private final CopilotCompactionRepository contexts;
    private final IdentityTransactionService identity;
    private final ExerciseTransactionService exercises;

    public CopilotUsageService(CopilotUsageRepository usage, CopilotCompactionRepository contexts,
        IdentityTransactionService identity, ExerciseTransactionService exercises) {
        this.usage = usage; this.contexts = contexts; this.identity = identity; this.exercises = exercises;
    }

    /** Private delivery serializes against revocation using the owner row lock; no model dispatch or bookkeeping writes. */
    @Transactional
    public CopilotUsageResponse current(String session, String exercise) {
        var grant = identity.authorizePrivateDelivery(session);
        if (!"USER".equals(grant.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        exercises.find(session, exercise);
        var head = contexts.head(exercise);
        var latest = usage.latest(exercise);
        long version = head == null ? 0 : head.version();
        boolean waiting = version > 0 && (latest == null || latest.contextVersion() == null || version > latest.contextVersion());
        String input = waiting || latest == null ? null : latest.inputTokens();
        var context = new CopilotUsageContextResponse(waiting ? "WAITING_UPDATE" : input == null ? "UNKNOWN" : "REPORTED",
            latest == null ? null : latest.turnId(), latest == null ? null : latest.modelId(), latest == null ? null : latest.contextWindow(),
            latest == null ? null : "USER_CONFIGURATION", input, latest == null ? null : latest.dispatchedAt());
        return new CopilotUsageResponse(exercise, context, usage.totals(exercise), usage.costs(exercise));
    }
}
