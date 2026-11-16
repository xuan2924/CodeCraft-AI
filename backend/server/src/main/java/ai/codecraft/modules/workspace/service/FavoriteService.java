package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.FavoriteResponse;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.FavoriteRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteService {
    private static final int MAX_FAVORITES = 1000;
    private final FavoriteRepository repository;
    private final IdentityTransactionService identity;

    public FavoriteService(FavoriteRepository repository, IdentityTransactionService identity) {
        this.repository = repository;
        this.identity = identity;
    }

    @Transactional(readOnly = true)
    public List<FavoriteResponse> list(String session) {
        return repository.list(identity.authorization(session).userId(), MAX_FAVORITES).stream()
            .map(entry -> new FavoriteResponse(entry.problemId(), entry.title(), entry.available())).toList();
    }

    @Transactional
    public void set(String session, String problem, Boolean favorite) {
        String user = identity.authorizeMutation(session).userId();
        if (favorite == null || problem == null || !problem.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        }
        if (!favorite) { repository.remove(user, problem); return; }
        if (repository.exists(user, problem)) return;
        if (repository.count(user) >= MAX_FAVORITES) throw new BusinessException(WorkspaceErrorCode.FAVORITE_LIMIT_REACHED);
        if (repository.add(user, problem) != 1) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
    }
}
