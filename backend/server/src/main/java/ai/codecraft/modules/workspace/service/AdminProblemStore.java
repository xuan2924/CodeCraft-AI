package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.AdminProblemResponse;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminProblemStore {
    private final IdentityTransactionService identity;
    private final ProblemRepository repository;
    private final ProblemTransactionService problems;
    public AdminProblemStore(IdentityTransactionService identity, ProblemRepository repository, ProblemTransactionService problems) {
        this.identity = identity;
        this.repository = repository;
        this.problems = problems;
    }
    public String authorize(String session, boolean mutation) {
        var actor = mutation ? identity.authorizeMutation(session) : identity.authorization(session);
        if (!"ADMIN".equals(actor.role()) || actor.passwordChangeRequired()) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        return actor.userId();
    }
    public List<AdminProblemResponse> list(String session) {
        authorize(session, false);
        return repository.adminList();
    }
    public AdminProblemResponse.Published replay(String session, String key, String digest) {
        String actor = authorize(session, false);
        validateKey(key);
        String previous = repository.fingerprint(actor, key);
        if (previous == null) return null;
        if (!previous.equals(digest)) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        return repository.bound(actor, key);
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
    }

    @Transactional
    public AdminProblemResponse.Published publish(String session, String key, String digest, ProblemVersionEntity version) {
        String actor = authorize(session, true);
        validateKey(key);
        if (repository.claim(actor, key, digest) == 0) {
            if (!digest.equals(repository.fingerprint(actor, key))) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        } else problems.publish(version);
        var result = repository.published(version.problemId(), version.version());
        repository.bind(actor, key, result.id());
        return result;
    }
    @Transactional
    public void availability(String session, String id, String key, Boolean available) {
        String actor = authorize(session, true);
        validateKey(key);
        if (available == null) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        if (repository.claimAvailability(actor, key, id, available) == 0) {
            if (!repository.sameAvailability(actor, key, id, available)) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return;
        }
        if (repository.availability(id, available) != 1) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
    }
}
