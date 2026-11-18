package ai.codecraft.modules.workspace.gateway;

import ai.codecraft.modules.workspace.config.SandboxNodesProperties;
import ai.codecraft.modules.workspace.config.WorkspaceProperties;
import ai.codecraft.modules.workspace.model.dto.SandboxNodeDTO;
import ai.codecraft.modules.workspace.repository.SandboxNodeRepository;
import ai.codecraft.workspace.v1.NodeStatusRequest;
import ai.codecraft.workspace.v1.WorkspaceStorageGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SandboxClients {
    private final List<SandboxNodesProperties.Node> nodes;
    private final Map<String, ManagedChannel> channels = new LinkedHashMap<>();
    private final SandboxNodeRepository repository;
    private final String key;
    public SandboxClients(SandboxNodesProperties configuration, WorkspaceProperties fallback, SandboxNodeRepository repository) {
        this.nodes = configuration.endpoints(fallback); this.repository = repository; this.key = fallback.rpcKey();
        for (var node : nodes) channels.put(node.id(), ManagedChannelBuilder.forAddress(node.host(), node.port()).usePlaintext()
            .maxInboundMessageSize(2 * 1024 * 1024).build());
    }
    public List<String> nodeIds() { return nodes.stream().map(SandboxNodesProperties.Node::id).toList(); }
    private Metadata metadata(String storage) {
        var metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-codecraft-internal-key", Metadata.ASCII_STRING_MARSHALLER), key);
        if (storage != null) metadata.put(Metadata.Key.of("x-codecraft-storage-id", Metadata.ASCII_STRING_MARSHALLER), storage);
        return metadata;
    }
    private ManagedChannel channel(SandboxNodeDTO node) {
        var configured = nodes.stream().filter(value -> value.id().equals(node.id()) && value.endpoint().equals(node.endpoint())).findFirst();
        if (configured.isEmpty()) throw new IllegalStateException("Bound sandbox endpoint not configured");
        return channels.get(node.id());
    }
    public WorkspaceStorageGrpc.WorkspaceStorageBlockingStub client(SandboxNodeDTO node) {
        return WorkspaceStorageGrpc.newBlockingStub(channel(node)).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata(node.storageId())))
            .withDeadlineAfter(3, TimeUnit.SECONDS);
    }
    public WorkspaceStorageGrpc.WorkspaceStorageStub stream(SandboxNodeDTO node) {
        return WorkspaceStorageGrpc.newStub(channel(node)).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata(node.storageId())));
    }
    @Scheduled(fixedDelay = 5000)
    public void probe() {
        for (var node : nodes) {
            try {
                repository.register(node.id(), node.endpoint());
                var status = WorkspaceStorageGrpc.newBlockingStub(channels.get(node.id()))
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata(null))).withDeadlineAfter(1, TimeUnit.SECONDS)
                    .nodeStatus(NodeStatusRequest.getDefaultInstance());
                if (!status.getStorageId().matches("[0-9a-f]{64}") || !status.getHostEpoch().matches("[A-Z2-7]{26}")
                    || status.getMaxSlots() < 1 || status.getMaxSlots() > 64 || status.getActiveSlots() > status.getMaxSlots()
                    || repository.healthy(node.id(), node.endpoint(), status.getStorageId(), status.getHostEpoch(), status.getActiveSlots(), status.getMaxSlots(), status.getReady()) != 1) {
                    repository.unhealthy(node.id());
                }
            } catch (RuntimeException unavailable) {
                try { repository.unhealthy(node.id()); }
                catch (RuntimeException databaseUnavailable) { /* The last health lease expires; never refresh on failure. */ }
            }
        }
    }
    @PreDestroy public void close() { channels.values().forEach(ManagedChannel::shutdownNow); }
}
