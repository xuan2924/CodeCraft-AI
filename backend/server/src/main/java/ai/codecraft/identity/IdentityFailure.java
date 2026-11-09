package ai.codecraft.identity;

public final class IdentityFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;

    public IdentityFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
