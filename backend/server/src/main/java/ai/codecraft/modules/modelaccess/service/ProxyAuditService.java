package ai.codecraft.modules.modelaccess.service;

import ai.codecraft.modules.modelaccess.repository.ProxyAuditRepository;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCostSubtotalResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Numeric metadata and protocol signals only; never persists raw prompts, responses or credentials. */
@Service
public class ProxyAuditService implements ai.codecraft.modules.workspace.service.ExerciseDataContributor {
    private final ProxyAuditRepository repository;
    private final Clock clock;
    public ProxyAuditService(ProxyAuditRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }
    @Transactional
    public String start(String exercise, String endpoint, int bytes, String price) {
        String id = UUID.randomUUID().toString();
        repository.start(id, exercise, clock.instant(), endpoint, bytes, price);
        return id;
    }
    @Transactional
    public void finish(String id, String state, long bytes, int tools, Long reasoning, ModelUsageReceiptDTO receipt) {
        repository.finish(id, clock.instant(), state, bytes, tools, reasoning, receipt);
    }
    public List<ProxyAuditRepository.Event> events(String exercise) { return repository.events(exercise); }
    public List<ModelUsageTotalsResponse> totals(String exercise) { return repository.totals(exercise); }
    public List<ModelCostSubtotalResponse> costs(String exercise) { return repository.costs(exercise); }
    @Override public long retainedBytes(String exercise) { return repository.bytes(exercise); }
    @Override public boolean quiescent(String exercise) { return repository.quiescent(exercise); }
    @Override public void erase(String exercise) { repository.delete(exercise); }
}
