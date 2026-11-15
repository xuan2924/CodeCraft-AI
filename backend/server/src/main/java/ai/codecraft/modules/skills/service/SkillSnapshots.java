package ai.codecraft.modules.skills.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.skills.model.dto.SkillSnapshotDTO;
import ai.codecraft.modules.skills.model.dto.SkillDocumentDTO;
import ai.codecraft.modules.skills.model.dto.SkillSnapshotSourceDTO;
import ai.codecraft.modules.skills.model.exception.SkillErrorCode;
import ai.codecraft.modules.skills.repository.SkillSnapshotRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal cross-module boundary. Caller supplies a freshly authorized task owner;
 * admission and mutation must share that owner's existing transaction/lock. */
@Service
public class SkillSnapshots {
    private final SkillSnapshotRepository repository;
    public SkillSnapshots(SkillSnapshotRepository repository) { this.repository = repository; }
    @Transactional(propagation = Propagation.MANDATORY)
    public long additionalBytes(String owner, String exercise) { return repository.additionalBytes(owner, exercise); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void pin(String owner, String task, String exercise, List<String> selected) {
        var enabled = repository.enabled(owner);
        var ids = new HashSet<>(selected);
        if (ids.size() != selected.size() || !enabled.stream().map(SkillSnapshotDTO::skillId).toList().containsAll(ids)) {
            throw new BusinessException(SkillErrorCode.SKILL_NOT_FOUND);
        }
        if (!enabled.isEmpty()) repository.pin(owner, task, exercise, enabled.stream().map(item -> new SkillSnapshotDTO(item.skillId(), item.versionId(),
            item.name(), item.description(), ids.contains(item.skillId()), false)).toList());
    }
    @Transactional(readOnly = true)
    public List<SkillSnapshotDTO> list(String owner, String task) { return repository.list(owner, task); }
    @Transactional(readOnly = true)
    public SkillSnapshotSourceDTO source(String owner, String task, String skill) {
        var result = repository.source(owner, task, skill);
        if (result == null) throw new BusinessException(SkillErrorCode.SKILL_NOT_FOUND);
        return result;
    }
    @Transactional(readOnly = true)
    public List<SkillDocumentDTO> explicitDocuments(String owner, String task, long maximum) {
        return repository.explicitDocuments(owner, task, maximum);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void markExplicitLoaded(String owner, String task, Instant now) { repository.markExplicitLoaded(owner, task, now); }
}
