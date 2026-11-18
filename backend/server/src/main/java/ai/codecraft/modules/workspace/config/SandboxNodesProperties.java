package ai.codecraft.modules.workspace.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("workspace.sandbox")
public record SandboxNodesProperties(String nodes) {
    public SandboxNodesProperties { nodes = nodes == null ? "" : nodes; }
    public List<Node> endpoints(WorkspaceProperties fallback) {
        if (nodes.isBlank()) return List.of(new Node("default", fallback.sandboxHost(), fallback.sandboxPort()));
        var result = Arrays.stream(nodes.split(",", -1)).map(value -> {
            if (!value.matches("[a-z][a-z0-9-]{0,31}@[A-Za-z0-9.-]+:[0-9]{1,5}")) throw new IllegalArgumentException("Invalid sandbox node");
            String[] identity = value.split("@", 2); String[] address = identity[1].split(":", 2);
            return new Node(identity[0], address[0], Integer.parseInt(address[1]));
        }).toList();
        if (result.size() > 16 || result.stream().map(Node::id).distinct().count() != result.size()
            || result.stream().map(Node::endpoint).distinct().count() != result.size()
            || result.stream().noneMatch(node -> node.id().equals("default"))) throw new IllegalArgumentException("Nodes require unique endpoints and default legacy node");
        return result;
    }
    public record Node(String id, String host, int port) {
        public Node { if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid sandbox port"); }
        public String endpoint() { return host + ":" + port; }
    }
}
