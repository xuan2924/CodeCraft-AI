package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProblemTransactionService {
    private final ProblemRepository repository;
    public ProblemTransactionService(ProblemRepository repository) { this.repository = repository; }

    @Transactional
    public void publish(ProblemVersionEntity version) {
        repository.insert(version);
        if (!repository.matches(version)) throw new BusinessException(WorkspaceErrorCode.PUBLICATION_CONFLICT);
    }

    @Transactional(readOnly = true)
    public List<ProblemVersionEntity> list() { return repository.list(); }

    @Transactional(readOnly = true)
    public ProblemVersionEntity find(String id) {
        ProblemVersionEntity version = repository.find(id);
        if (version == null) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        return version;
    }
}
