package ai.codecraft.modules.workspace.gateway;

import ai.codecraft.modules.workspace.model.entity.WorkspaceChangeEntity;
import ai.codecraft.workspace.v1.WorkspaceHistoryRequest;
import ai.codecraft.workspace.v1.WorkspaceHistoryPreview;

import ai.codecraft.modules.workspace.config.WorkspaceProperties;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.workspace.v1.CommandSnapshot;
import ai.codecraft.workspace.v1.CommandChanges;
import ai.codecraft.workspace.v1.StartCommandRequest;
import ai.codecraft.modules.workspace.model.entity.TestStopEntity;
import ai.codecraft.modules.workspace.model.entity.FinalTaskAuthorityEntity;
import ai.codecraft.workspace.v1.FinalVerificationAuthority;
import ai.codecraft.workspace.v1.StopTaskRequest;
import ai.codecraft.workspace.v1.ExecutionInventory;
import ai.codecraft.workspace.v1.ExecutionInventoryRequest;
import ai.codecraft.workspace.v1.StopTaskResponse;
import ai.codecraft.workspace.v1.StartTestRequest;
import ai.codecraft.workspace.v1.QueryTaskRequest;
import ai.codecraft.workspace.v1.TestTaskSnapshot;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.QueryMutationRequest;
import ai.codecraft.workspace.v1.CancelPreparationRequest;
import ai.codecraft.workspace.v1.CloseGateRequest;
import ai.codecraft.workspace.v1.GateResponse;
import ai.codecraft.workspace.v1.PrepareWorkspaceRequest;
import ai.codecraft.workspace.v1.ProblemIdentity;
import ai.codecraft.workspace.v1.QueryWorkspaceRequest;
import ai.codecraft.workspace.v1.ReadWorkspaceFileRequest;
import ai.codecraft.workspace.v1.GrepWorkspaceRequest;
import ai.codecraft.workspace.v1.GrepWorkspaceResponse;
import ai.codecraft.workspace.v1.ReadWorkspaceFileResponse;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import ai.codecraft.workspace.v1.WorkspaceDeletionReceipt;
import ai.codecraft.workspace.v1.CollectWorkspaceRequest;
import ai.codecraft.workspace.v1.WorkspaceCollectionReceipt;
import ai.codecraft.workspace.v1.TestEvidencePruningReceipt;
import ai.codecraft.modules.workspace.model.dto.TestHistoryRecord;
import ai.codecraft.modules.workspace.model.dto.StorageScope;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.workspace.v1.MeasureExerciseStorageRequest;
import ai.codecraft.workspace.v1.WorkspaceStorageGrpc;
import io.grpc.Context;
import ai.codecraft.workspace.v1.WatchTaskRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class SandboxWorkspaceGateway {
    private final ManagedChannel channel;
    private final WorkspaceStorageGrpc.WorkspaceStorageBlockingStub stub;

    public SandboxWorkspaceGateway(WorkspaceProperties properties) {
        channel = ManagedChannelBuilder.forAddress(properties.sandboxHost(), properties.sandboxPort()).usePlaintext()
            .maxInboundMessageSize(2 * 1024 * 1024).build();
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-codecraft-internal-key", Metadata.ASCII_STRING_MARSHALLER), properties.rpcKey());
        stub = WorkspaceStorageGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    public StorageMeasurement measureStorage(StorageScope scope) {
        ai.codecraft.workspace.v1.StorageMeasurement measured = null;
        int size = Math.max(1, Math.max(scope.workspaceInstances().size(), scope.executions().size()));
        for (int offset = 0; offset < size; offset += 1000) {
            var request = MeasureExerciseStorageRequest.newBuilder().setExerciseId(scope.exerciseId());
            if (offset < scope.workspaceInstances().size()) request.addAllWorkspaceInstanceIds(scope.workspaceInstances().subList(offset, Math.min(offset + 1000, scope.workspaceInstances().size())));
            if (offset < scope.executions().size()) {
                for (var execution : scope.executions().subList(offset, Math.min(offset + 1000, scope.executions().size()))) {
                    request.addExecutions(QueryTaskRequest.newBuilder().setExerciseId(execution.exerciseId()).setTaskId(execution.taskId())
                        .setWorkspaceInstanceId(execution.workspaceInstanceId()).setExecutionInstanceId(execution.executionInstanceId()));
                }
            }
            measured = client().measureExerciseStorage(request.build());
            if (!measured.getExerciseId().equals(scope.exerciseId()) || !measured.getMeterEpoch().matches("[A-Z2-7]{26}")
                || measured.getSequence() < 1 || measured.getBytes() < 0) throw new IllegalStateException("Invalid storage measurement");
        }
        if (measured == null) throw new IllegalStateException("Missing storage measurement");
        return new StorageMeasurement(measured.getExerciseId(), measured.getMeterEpoch(), measured.getSequence(), measured.getBytes());
    }

    public TestEvidencePruningReceipt pruneTestEvidence(String exercise, TestHistoryRecord record) {
        return client().pruneTestEvidence(QueryTaskRequest.newBuilder().setExerciseId(exercise).setTaskId(record.taskId())
            .setWorkspaceInstanceId(record.workspaceInstanceId()).setExecutionInstanceId(record.executionInstanceId()).build());
    }

    public WorkspaceCollectionReceipt collectWorkspace(String instance, long revision, java.util.List<Long> references) {
        return client().collectWorkspace(CollectWorkspaceRequest.newBuilder().setWorkspaceInstanceId(instance)
            .setExpectedRevision(revision).addAllRetainedRevisions(references).build());
    }

    public WorkspaceDeletionReceipt deleteWorkspace(String instance) {
        return client().deleteWorkspace(QueryWorkspaceRequest.newBuilder().setWorkspaceInstanceId(instance).build());
    }

    public ExecutionInventory inventory(String after, String epoch) {
        ExecutionInventory page = client().listExecutions(ExecutionInventoryRequest.newBuilder()
            .setAfterExecutionId(after).setHostEpoch(epoch).setLimit(100).build());
        if (!page.getHostEpoch().matches("[A-Z2-7]{26}") || page.getEntriesCount() > 100) throw new IllegalStateException("Invalid execution inventory");
        return page;
    }

    public CommandSnapshot startCommand(CommandTaskEntity task) {
        return client().startCommand(StartCommandRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
            .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId())
            .setExpectedRevision(task.revision()).setGateEpoch(task.gateEpoch()).setNotAfterUnixMillis(task.notAfter().toEpochMilli())
            .setHostEpoch(task.hostEpoch()).setCommand(task.commandText()).setDirectory(task.workingDirectory()).build());
    }

    public CommandSnapshot command(CommandTaskEntity task) {
        try { return client().queryCommand(commandIdentity(task)); }
        catch (StatusRuntimeException error) {
            if (error.getStatus().getCode() == Status.Code.NOT_FOUND) return null;
            throw error;
        }
    }

    public void reconcileCommand(CommandTaskEntity task) { client().reconcileTask(commandIdentity(task)); }

    public StopTaskResponse stopCommand(CommandTaskEntity task) { return client().stopSystemTask(commandIdentity(task)); }

    /** Reads a bounded historical projection without authorizing a write or starting an execution. */
    public WorkspaceHistoryPreview historyPreview(
        WorkspaceChangeEntity change, long currentRevision, String mode, Long targetRevision) {
        var request = WorkspaceHistoryRequest.newBuilder().setWorkspaceInstanceId(change.workspaceInstanceId())
            .setBaseRevision(change.baseRevision()).setResultRevision(change.resultRevision()).setExpectedRevision(currentRevision)
            .setMode(mode).setTargetRevision(targetRevision == null ? 0 : targetRevision).build();
        return client().withMaxInboundMessageSize(24 * 1024 * 1024).previewWorkspaceHistory(request);
    }

    public CommandChanges commandChanges(CommandTaskEntity task) {
        // Both the original and final managed trees can be 10 MiB; other RPCs
        // retain their existing small inbound limit.
        return client().withMaxInboundMessageSize(24 * 1024 * 1024).readCommandChanges(commandIdentity(task));
    }

    public TestEvidencePruningReceipt pruneCommand(CommandTaskEntity task) { return client().pruneCommandEvidence(commandIdentity(task)); }

    private static QueryTaskRequest commandIdentity(CommandTaskEntity task) {
        return QueryTaskRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
            .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId())
            .setObservedStateVersion(task.stateVersion()).build();
    }

    public TestTaskSnapshot startTest(TestTaskEntity task) { return startTest(task, null); }

    public TestTaskSnapshot startTest(TestTaskEntity task, FinalTaskAuthorityEntity authority) {
        var request = StartTestRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
            .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId())
            .setExpectedRevision(task.revision()).setGateEpoch(task.gateEpoch()).setNotAfterUnixMillis(task.notAfter().toEpochMilli())
            .setTarget(task.target()).setPurpose("MANUAL").setHostEpoch(task.hostEpoch());
        if (authority != null) request.setPurpose("FINAL_VERIFICATION").setFinalVerification(FinalVerificationAuthority.newBuilder()
            .setFinalizationId(authority.finalizationId()).setAttemptId(authority.taskId()).setFreezeOperationId(authority.freezeOperationId())
            .setDeadlineUnixMillis(authority.deadlineAt().toEpochMilli()));
        return client().startTest(request.build());
    }

    public StopTaskResponse stopSystemTask(TestTaskEntity task) {
        return client().stopSystemTask(QueryTaskRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
            .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId()).build());
    }

    public GateResponse freeze(String instance, String operation, long epoch) {
        return client().freeze(CloseGateRequest.newBuilder().setWorkspaceInstanceId(instance).setTransitionId(operation).setExpectedEpoch(epoch).build());
    }

    public TestTaskSnapshot task(TestTaskEntity task) {
        try {
            return client().queryTask(QueryTaskRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
                .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId()).build());
        } catch (StatusRuntimeException error) {
            if (error.getStatus().getCode() == Status.Code.NOT_FOUND) return null;
            throw error;
        }
    }

    /** Bounded observation only. Cancellation releases WatchTask, never execution ownership. */
    public boolean awaitTaskEvent(TestTaskEntity task, TestTaskSnapshot checkpoint) {
        if (checkpoint.hasFinishedAtUnixMillis() || checkpoint.getStreamId().isBlank()) return false;
        try (Context.CancellableContext observation = Context.current().withCancellation()) {
            Context previous = observation.attach();
            try {
                var events = stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS).watchTask(WatchTaskRequest.newBuilder()
                    .setTask(QueryTaskRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
                        .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId()))
                    .setStreamId(checkpoint.getStreamId()).setAfterSequence(checkpoint.getSequence()).build());
                if (!events.hasNext()) return false;
                var event = events.next();
                if (!event.getTaskId().equals(task.id()) || !event.getExecutionInstanceId().equals(task.executionInstanceId())) {
                    throw new IllegalStateException("Invalid task event identity");
                }
                // Coalesce into an authoritative checkpoint, including on reset_required.
                // This avoids publishing a cursor ahead of its materialized log tail.
                return true;
            } catch (StatusRuntimeException error) {
                if (error.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) return false;
                throw error;
            } finally { observation.detach(previous); }
        }
    }

    public StopTaskResponse stopTask(TestTaskEntity task, TestStopEntity stop) {
        return client().stopTask(StopTaskRequest.newBuilder().setTask(QueryTaskRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
            .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId()))
            .setGateEpoch(stop.gateEpoch()).setNotAfterUnixMillis(stop.notAfter().toEpochMilli()).build());
    }

    public StopTaskResponse queryStop(TestTaskEntity task) {
        return client().queryStop(QueryTaskRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
            .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId()).build());
    }

    public TestTaskSnapshot reconcileTask(TestTaskEntity task) {
        return client().reconcileTask(QueryTaskRequest.newBuilder().setExerciseId(task.exerciseId()).setTaskId(task.id())
            .setExecutionInstanceId(task.executionInstanceId()).setWorkspaceInstanceId(task.workspaceInstanceId())
            .setObservedStateVersion(task.stateVersion()).build());
    }

    public WorkspaceSnapshot prepare(WorkspaceOperationEntity operation, ProblemVersionEntity problem, Instant notAfter) {
        try {
            return client().queryWorkspace(QueryWorkspaceRequest.newBuilder()
                .setWorkspaceInstanceId(operation.workspaceInstanceId()).build());
        } catch (StatusRuntimeException error) {
            if (error.getStatus().getCode() != Status.Code.NOT_FOUND) throw error;
        }
        return client().prepareWorkspace(PrepareWorkspaceRequest.newBuilder()
            .setWorkspaceInstanceId(operation.workspaceInstanceId()).setOperationId(operation.id()).setNotAfterUnixMillis(notAfter.toEpochMilli())
            .setProblem(ProblemIdentity.newBuilder().setProblemId(problem.problemId()).setVersion(problem.version())
                .setPackageSha256(problem.packageSha256()).setExecutionImageDigest(problem.executionImageDigest())
                .setPlatform(problem.platform())).build());
    }

    public void closeGate(String instance, String transition) {
        GateResponse gate = client().closeGate(CloseGateRequest.newBuilder().setWorkspaceInstanceId(instance)
            .setTransitionId(transition).setExpectedEpoch(0).build());
        if (!gate.getWorkspaceInstanceId().equals(instance) || !gate.getTransitionId().equals(transition)
            || gate.getEpoch() != 1 || !gate.getState().equals("CLOSED")) {
            throw new IllegalStateException("Invalid revocation fence receipt");
        }
    }

    public GateResponse gate(String instance) {
        return client().queryGate(QueryWorkspaceRequest.newBuilder().setWorkspaceInstanceId(instance).build());
    }

    public GateResponse closeGate(String instance, String transition, long epoch) {
        return client().closeGate(CloseGateRequest.newBuilder().setWorkspaceInstanceId(instance)
            .setTransitionId(transition).setExpectedEpoch(epoch).build());
    }

    public GateResponse openGate(String instance, String transition, long epoch) {
        return client().openGate(CloseGateRequest.newBuilder().setWorkspaceInstanceId(instance)
            .setTransitionId(transition).setExpectedEpoch(epoch).build());
    }

    public MutationReceipt apply(ApplyBatchRequest request) { return client().applyBatch(request); }

    public MutationReceipt mutation(String instance, String request) {
        try {
            return client().queryMutation(QueryMutationRequest.newBuilder().setWorkspaceInstanceId(instance).setRequestId(request).build());
        } catch (StatusRuntimeException error) {
            if (error.getStatus().getCode() == Status.Code.NOT_FOUND) return null;
            throw error;
        }
    }

    public WorkspaceSnapshot query(String instance) {
        try {
            return client().queryWorkspace(QueryWorkspaceRequest.newBuilder().setWorkspaceInstanceId(instance).build());
        } catch (StatusRuntimeException error) {
            if (error.getStatus().getCode() == Status.Code.NOT_FOUND) return null;
            throw error;
        }
    }

    public WorkspaceSnapshot cancel(String instance, String operation) {
        return client().cancelPreparation(CancelPreparationRequest.newBuilder()
            .setWorkspaceInstanceId(instance).setOperationId(operation).build());
    }

    public ReadWorkspaceFileResponse read(String instance, String path, long revision) {
        return client().readWorkspaceFile(ReadWorkspaceFileRequest.newBuilder().setWorkspaceInstanceId(instance)
            .setPath(path).setExpectedRevision(revision).build());
    }

    public GrepWorkspaceResponse grep(GrepWorkspaceRequest request) {
        return client().grepWorkspace(request);
    }

    private WorkspaceStorageGrpc.WorkspaceStorageBlockingStub client() {
        return stub.withDeadlineAfter(3, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void close() { channel.shutdownNow(); }
}
