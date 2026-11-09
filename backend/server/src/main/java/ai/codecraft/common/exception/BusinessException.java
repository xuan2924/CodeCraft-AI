package ai.codecraft.common.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode).code());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
    public int status() { return errorCode.status(); }
    public String code() { return errorCode.code(); }
}
