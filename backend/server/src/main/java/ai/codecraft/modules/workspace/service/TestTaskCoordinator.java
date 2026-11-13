package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.dto.TestEvidenceResponse;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.TestStopEntity;
import ai.codecraft.workspace.v1.StopTaskResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import ai.codecraft.workspace.v1.GateResponse;
import ai.codecraft.workspace.v1.ProblemIdentity;
import ai.codecraft.workspace.v1.TestTaskSnapshot;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Persistent dispatch is claimed once. Recovery queries/fences; it never replays Start. */
@Service
public class TestTaskCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(TestTaskCoordinator.class);
    private static final Set<String> STATES = Set.of("STARTING", "RUNNING", "STOPPING", "PASSED", "FAILED", "TIMED_OUT", "STOPPED", "ENVIRONMENT_ERROR", "INTERRUPTED");
    private final TestTaskStore store;
    private final SandboxWorkspaceGateway sandbox;
    private final WorkspaceCoordinationStore coordination;
    private final WorkspaceWriteCoordinator fences;
    private String inventoryCursor = "";
    private String inventoryEpoch = "";

    public TestTaskCoordinator(TestTaskStore store, SandboxWorkspaceGateway sandbox, WorkspaceCoordinationStore coordination,
        WorkspaceWriteCoordinator fences) {
        this.store = store; this.sandbox = sandbox; this.coordination = coordination; this.fences = fences;
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        for (TestStopEntity stop : store.pendingStops()) tryStop(stop);
        for (TestTaskEntity task : store.pending()) {
            try { observe(task); }
            catch (RuntimeException error) { LOG.warn("Test task {} remains unresolved ({})", task.id(), error.getClass().getSimpleName()); }
        }
    }

    /** One bounded page per tick; a JVM restart safely begins from the first page. */
    @Scheduled(fixedDelay = 5000)
    public void reconcileInventory() {
        try {
            var page = sandbox.inventory(inventoryCursor, inventoryEpoch);
            var known = store.inventoryTasks(page.getEntriesList().stream().map(entry -> entry.getTask().getExecutionInstanceId()).toList());
            for (var entry : page.getEntriesList()) {
                var identity = entry.getTask();
                TestTaskEntity task = known.stream().filter(value -> value.executionInstanceId().equals(identity.getExecutionInstanceId())).findFirst().orElse(null);
                if (task == null) {
                    LOG.warn("Execution {} has no business receipt; retaining isolation", identity.getExecutionInstanceId());
                    continue;
                }
                if (!task.id().equals(identity.getTaskId()) || !task.exerciseId().equals(identity.getExerciseId())
                    || !task.workspaceInstanceId().equals(identity.getWorkspaceInstanceId())) throw new IllegalStateException("Inventory owner mismatch");
                if (!entry.getQuiescent() || !entry.getCleanup().equals("SUCCEEDED") || !entry.getCopyCleanup().equals("SUCCEEDED")) observe(task);
            }
            inventoryCursor = page.getNextExecutionId();
            inventoryEpoch = inventoryCursor.isEmpty() ? "" : page.getHostEpoch();
        } catch (RuntimeException error) {
            inventoryCursor = ""; inventoryEpoch = "";
            LOG.warn("Execution inventory remains unresolved ({})", error.getClass().getSimpleName());
        }
    }

    public void tryStop(TestStopEntity stop) {
        if (stop == null || !stop.state().equals("PENDING")) return;
        try {
            store.stopAttempted(stop);
            TestTaskEntity task = store.find(stop.taskId());
            if (recoverStopReceipt(task, stop)) return;
            StopTaskResponse response = stop.ownerRecovery() ? sandbox.stopSystemTask(task) : sandbox.stopTask(task, stop);
            if (response.hasSnapshot()) confirm(task, response.getSnapshot());
            else if (!response.getCancelledBeforeRegistration()) throw new IllegalStateException("Missing cancellation receipt");
            store.stopObserved(stop, true, response.getCancelledBeforeRegistration(), response.getHostEpoch());
        } catch (StatusRuntimeException error) {
            if (error.getStatus().getCode() == Status.Code.FAILED_PRECONDITION && !stop.ownerRecovery()) {
                try {
                    // A previous delayed stop may have committed before the gate
                    // closed. Read its receipt AFTER the authority rejection.
                    if (!recoverStopReceipt(store.find(stop.taskId()), stop)) store.stopObserved(stop, false, false, "");
                } catch (RuntimeException unavailable) {
                    LOG.warn("Stop {} receipt remains unresolved ({})", stop.id(), unavailable.getClass().getSimpleName());
                }
            } else LOG.warn("Stop {} remains unresolved ({})", stop.id(), error.getClass().getSimpleName());
        } catch (RuntimeException error) {
            LOG.warn("Stop {} remains unresolved ({})", stop.id(), error.getClass().getSimpleName());
        }
    }

    private boolean recoverStopReceipt(TestTaskEntity task, TestStopEntity stop) {
        StopTaskResponse receipt = sandbox.queryStop(task);
        if (receipt.getCancelledBeforeRegistration()) { store.stopObserved(stop, true, true, receipt.getHostEpoch()); return true; }
        if (receipt.hasSnapshot() && (receipt.getSnapshot().hasFinishedAtUnixMillis() || receipt.getSnapshot().getState().equals("STOPPING"))) {
            confirm(task, receipt.getSnapshot());
            store.stopObserved(stop, true, false, receipt.getHostEpoch());
            return true;
        }
        return false;
    }

    public void stopSystem(TestTaskEntity task) {
        StopTaskResponse response = sandbox.stopSystemTask(task);
        if (response.hasSnapshot()) confirm(task, response.getSnapshot());
        else if (response.getCancelledBeforeRegistration()) {
            store.systemCancelledBeforeRegistration(task.id(), response.getHostEpoch());
        }
        else throw new IllegalStateException("Missing system stop receipt");
    }

    public void observe(TestTaskEntity original) {
        String hostEpoch = original.dispatched() ? original.hostEpoch() : sandbox.inventory("", "").getHostEpoch();
        if (store.claim(original, hostEpoch)) {
            // A lost response remains dispatched forever. The next observation
            // queries the same ID rather than launching a replacement container.
            TestTaskEntity dispatched = store.find(original.id());
            TestTaskSnapshot result = sandbox.startTest(dispatched, store.finalAuthority(dispatched.id()));
            confirm(dispatched, result);
            return;
        }
        TestTaskEntity task = store.find(original.id());
        if (!task.dispatched()) return;
        TestTaskSnapshot result = sandbox.task(task);
        if (result != null) {
            confirm(task, result);
            sandbox.reconcileTask(task);
            if (sandbox.awaitTaskEvent(task, result)) {
                TestTaskSnapshot checkpoint = sandbox.task(task);
                if (checkpoint != null) confirm(task, checkpoint);
            }
            return;
        }
        // Refresh physical inventory before treating an absent registration as
        // a never-dispatched task. Lost files can be reconstructed for cleanup.
        String currentHost = sandbox.inventory("", "").getHostEpoch();
        result = sandbox.task(task);
        if (result != null) { confirm(task, result); sandbox.reconcileTask(task); return; }
        GateResponse gate = sandbox.gate(task.workspaceInstanceId());
        if (!gate.getWorkspaceInstanceId().equals(task.workspaceInstanceId())) throw new IllegalStateException("Invalid task fence identity");
        if (store.finalAuthority(task.id()) != null) {
            // A frozen epoch cannot be advanced by reopening/closing. An exact
            // cancellation tombstone fences a missing system registration instead.
            stopSystem(task);
            return;
        }
        if (gate.getEpoch() <= task.gateEpoch()) {
            fences.drive(coordination.reconcile(task.exerciseId()));
            gate = sandbox.gate(task.workspaceInstanceId());
        }
        if (gate.getEpoch() <= task.gateEpoch()) throw new IllegalStateException("Task dispatch not fenced");
        // The absence observation MUST follow the durable fence. A Start that
        // registered before it is preserved; a late old-epoch Start cannot register.
        result = sandbox.task(task);
        if (result == null) store.notRegisteredAfterFence(task, !currentHost.equals(task.hostEpoch()));
        else confirm(task, result);
    }

    private void confirm(TestTaskEntity task, TestTaskSnapshot snapshot) {
        ProblemVersionEntity expected = store.problem(task);
        ProblemIdentity problem = snapshot.getProblem();
        if (!problem.getProblemId().equals(expected.problemId()) || !problem.getVersion().equals(expected.version())
            || !problem.getPackageSha256().equals(expected.packageSha256()) || !problem.getExecutionImageDigest().equals(expected.executionImageDigest())
            || !problem.getPlatform().equals(expected.platform()) || !STATES.contains(snapshot.getState())
            || snapshot.getRevision() < 0 || snapshot.getDroppedBytes() < 0 || snapshot.getLogsCount() > 4096
            || snapshot.getLogsList().stream().anyMatch(log -> !Set.of("stdout", "stderr").contains(log.getChannel()) || log.getData().size() > 16384)
            || snapshot.getLogsList().stream().mapToLong(log -> log.getData().size()).sum() > 1048576
            || !Set.of("PENDING", "SUCCEEDED", "FAILED").contains(snapshot.getCleanup())
            || !Set.of("PENDING", "SUCCEEDED", "FAILED").contains(snapshot.getCopyCleanup())
            || (snapshot.getQuiescent() && !snapshot.hasFinishedAtUnixMillis())) throw new IllegalStateException("Invalid task evidence");
        TestEvidenceResponse evidence = null;
        if (snapshot.hasEvidence()) {
            var source = snapshot.getEvidence();
            if (!Set.of("SUREFIRE_XML", "COMPILATION_DIAGNOSTICS").contains(source.getKind()) || !source.getSha256().matches("[a-f0-9]{64}")
                || source.getBytes() <= 0 || source.getBytes() > (source.getKind().equals("SUREFIRE_XML") ? 2097152 : 16384)
                || !Set.of("PASSED", "FAILED").contains(snapshot.getState())
                || (source.getKind().equals("COMPILATION_DIAGNOSTICS") && (!snapshot.getState().equals("FAILED") || !snapshot.getReason().equals("compilation_failed")))) {
                throw new IllegalStateException("Invalid task artifact metadata");
            }
            evidence = new TestEvidenceResponse(source.getKind(), source.getSha256(), source.getBytes());
        }
        if (Set.of("PASSED", "FAILED").contains(snapshot.getState()) && evidence == null) throw new IllegalStateException("Grading evidence missing");
        // jsonb cannot represent NUL; keep original bytes in Go and make NUL visible in this text projection.
        TestTaskResponse result = new TestTaskResponse(snapshot.getTaskId(), snapshot.getExerciseId(), snapshot.getExecutionInstanceId(), snapshot.getWorkspaceInstanceId(),
            Long.toString(snapshot.getRevision()), snapshot.getTarget(), snapshot.getState(), snapshot.getReason(), Instant.ofEpochMilli(snapshot.getStartedAtUnixMillis()),
            snapshot.hasFinishedAtUnixMillis() ? Instant.ofEpochMilli(snapshot.getFinishedAtUnixMillis()) : null,
            snapshot.hasExitCode() ? snapshot.getExitCode() : null, snapshot.getQuiescent(), snapshot.getCleanup(), snapshot.getCopyCleanup(),
            Long.toString(snapshot.getDroppedBytes()), snapshot.getLogsIncomplete(), snapshot.getLogsList().stream().map(log -> new TestLogResponse(log.getChannel(), log.getData().toStringUtf8().replace('\0', '\u2400'))).toList(), TestTaskMapper.problem(expected), evidence);
        store.observe(task, result, snapshot.getStateVersion(), snapshot.getStreamId(), snapshot.getSequence());
    }
}
