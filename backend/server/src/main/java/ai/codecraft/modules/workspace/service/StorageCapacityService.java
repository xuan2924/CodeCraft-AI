package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.StorageBudgetResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.StorageObservationDTO;
import ai.codecraft.modules.workspace.model.dto.StorageReconciliationPlan;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import io.grpc.StatusRuntimeException;
import java.util.function.Function;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Measurement/collection are outside DB transactions; only admission is retried. */
@Service
public class StorageCapacityService {
    private final StorageCapacityStore store;
    private final SandboxWorkspaceGateway sandbox;
    private final WorkspaceMaintenanceCoordinator maintenance;

    public StorageCapacityService(StorageCapacityStore store, SandboxWorkspaceGateway sandbox, WorkspaceMaintenanceCoordinator maintenance) {
        this.store = store; this.sandbox = sandbox; this.maintenance = maintenance;
    }

    public <T> T admit(String session, String exercise, Function<StorageMeasurement, T> acceptance) {
        return admit(session, exercise, Set.of(), acceptance);
    }

    /** Protects the selected history item during this request's own capacity retry. */
    public <T> T admit(String session, String exercise, Set<String> protectedHistory, Function<StorageMeasurement, T> acceptance) {
        return admit(session, exercise, protectedHistory, acceptance, false);
    }

    public <T> T admitHeavy(String session, String exercise, Function<StorageMeasurement, T> acceptance) {
        return admit(session, exercise, Set.of(), acceptance, true);
    }

    private <T> T admit(String session, String exercise, Set<String> protectedHistory, Function<StorageMeasurement, T> acceptance, boolean heavy) {
        var protectedIds = Set.copyOf(protectedHistory);
        var cached = heavy ? null : store.cachedMeasurement(session, exercise);
        if (cached != null) {
            try { return acceptance.apply(cached); }
            catch (StorageCapacityStore.CalibrationRequired retry) { /* admission transaction rolled back */ }
        }
        try { return acceptance.apply(measure(session, exercise)); }
        catch (BusinessException failure) {
            if (failure.errorCode() != WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED) throw failure;
            maintenance.collectForAdmission(exercise, protectedIds);
            return acceptance.apply(measure(session, exercise));
        }
    }

    public StorageMeasurement measure(String session, String exercise) { return observe(store.plan(session, exercise)).measurement(); }
    public StorageBudgetResponse usage(String session, String exercise) {
        var cached = store.cachedUsage(session, exercise);
        if (cached != null) return cached;
        var plan = store.plan(session, exercise);
        return store.deliver(session, plan, capture(plan));
    }
    public StorageMeasurement reconcile(String exercise) {
        var plan = store.plan(exercise);
        return plan == null ? null : observe(plan).measurement();
    }

    private StorageObservationDTO observe(StorageReconciliationPlan plan) {
        var measured = capture(plan);
        return new StorageObservationDTO(measured, store.reconcile(plan, measured));
    }

    private StorageMeasurement capture(StorageReconciliationPlan plan) {
        try { return sandbox.measureStorage(plan.scope()); }
        catch (StatusRuntimeException | IllegalStateException unavailable) {
            throw new BusinessException(WorkspaceErrorCode.STORAGE_METER_UNAVAILABLE, unavailable);
        }
    }
}
