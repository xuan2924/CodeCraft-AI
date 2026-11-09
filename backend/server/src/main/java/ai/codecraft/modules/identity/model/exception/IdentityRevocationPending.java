package ai.codecraft.modules.identity.model.exception;

public final class IdentityRevocationPending extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String operationId;
    public IdentityRevocationPending(String operationId) {
        super("Identity revocation awaits durable fencing");
        this.operationId = operationId;
    }
    public String operationId() { return operationId; }
}
