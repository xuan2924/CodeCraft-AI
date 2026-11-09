package ai.codecraft.modules.workspace.gateway;

import ai.codecraft.modules.workspace.config.WorkspaceProperties;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
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
import ai.codecraft.workspace.v1.ReadWorkspaceFileResponse;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import ai.codecraft.workspace.v1.WorkspaceStorageGrpc;
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
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", properties.sandboxPort()).usePlaintext()
            .maxInboundMessageSize(2 * 1024 * 1024).build();
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-codecraft-internal-key", Metadata.ASCII_STRING_MARSHALLER), properties.rpcKey());
        stub = WorkspaceStorageGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
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

    private WorkspaceStorageGrpc.WorkspaceStorageBlockingStub client() {
        return stub.withDeadlineAfter(3, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void close() { channel.shutdownNow(); }
}
