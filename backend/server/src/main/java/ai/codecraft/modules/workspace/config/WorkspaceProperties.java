package ai.codecraft.modules.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("workspace")
public record WorkspaceProperties(@DefaultValue("") String publicationFile, @DefaultValue("") String rpcKey,
                                  @DefaultValue("9090") int sandboxPort, @DefaultValue("127.0.0.1") String sandboxHost) {
    public WorkspaceProperties {
        if (sandboxHost == null || !sandboxHost.matches("[A-Za-z0-9.-]+")) throw new IllegalArgumentException("Invalid sandbox host");
        if (sandboxPort < 1 || sandboxPort > 65535) throw new IllegalArgumentException("Invalid sandbox port");
        if (!publicationFile.isBlank() && !rpcKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A published workspace requires a 32-byte hex SANDBOX_RPC_KEY");
        }
    }
    @Override public String toString() { return "WorkspaceProperties[publicationFile=" + publicationFile + ", rpcKey=<redacted>]"; }
}
