package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stage-aware public requirements only; never returns a raw publication or reference solution. */
@Service
public class ExerciseRequirementsService {
    private final ExerciseRepository exercises;
    private final ProblemRepository problems;
    private final ProblemPublicationReader publications;
    private final IdentityTransactionService identity;
    public ExerciseRequirementsService(ExerciseRepository exercises, ProblemRepository problems, ProblemPublicationReader publications, IdentityTransactionService identity) {
        this.exercises = exercises; this.problems = problems; this.publications = publications; this.identity = identity;
    }
    @Transactional
    public String read(String session, String exercise) {
        String user = identity.authorizePrivateDelivery(session).userId();
        var current = exercises.find(exercise);
        if (current == null || !user.equals(current.userId())) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(exercise)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        return published(exercise);
    }
    /** Trusted worker call after independent dispatch authorization. */
    @Transactional(readOnly = true)
    public String published(String exercise) {
        var current = exercises.find(exercise);
        if (current == null || exercises.deleted(exercise)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        var version = problems.find(current.problemVersionId());
        String base = publications.describe(version).requirements();
        if (!exercises.challengeEntered(exercise)) return "Current stage: BASE. Authoritative test target: base.\n" + base;
        var challenge = publications.challenge(version);
        if (challenge == null) throw new IllegalStateException("Entered challenge publication missing");
        return "Current stage: CHALLENGE. Authoritative test target: challenge (includes base regression).\nBase requirements:\n" + base
            + "\nPublished challenge requirements:\n" + challenge.requirements();
    }
}
