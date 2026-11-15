package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.skills.model.dto.SkillSnapshotDTO;
import ai.codecraft.modules.skills.model.dto.SkillSnapshotSourceDTO;
import ai.codecraft.modules.skills.service.SkillSnapshots;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotSkillService {
    private final IdentityTransactionService identity;
    private final CopilotTaskStore tasks;
    private final SkillSnapshots snapshots;
    public CopilotSkillService(IdentityTransactionService identity, CopilotTaskStore tasks, SkillSnapshots snapshots) {
        this.identity = identity; this.tasks = tasks; this.snapshots = snapshots;
    }
    @Transactional
    public List<SkillSnapshotDTO> current(String session, String task) {
        var grant = identity.authorizePrivateDelivery(session);
        tasks.find(session, task);
        if (!"USER".equals(grant.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        return snapshots.list(grant.userId(), task);
    }
    @Transactional
    public SkillSnapshotSourceDTO source(String session, String task, String skill) {
        var grant = identity.authorizePrivateDelivery(session);
        tasks.find(session, task);
        if (!"USER".equals(grant.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        return snapshots.source(grant.userId(), task, skill);
    }
}
