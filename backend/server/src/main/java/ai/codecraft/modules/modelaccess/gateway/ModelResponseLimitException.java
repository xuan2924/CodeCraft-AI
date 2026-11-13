package ai.codecraft.modules.modelaccess.gateway;

import java.io.IOException;

public final class ModelResponseLimitException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int statusCode;
    public ModelResponseLimitException(int statusCode) {
        super("Model response exceeds the configured byte limit"); this.statusCode = statusCode;
    }
    public int statusCode() { return statusCode; }
}
