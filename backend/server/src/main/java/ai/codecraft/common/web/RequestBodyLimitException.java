package ai.codecraft.common.web;

import ai.codecraft.common.exception.ErrorCode;

public class RequestBodyLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode errorCode;
    public RequestBodyLimitException(ErrorCode code) { super(code.code()); this.errorCode = code; }
    public ErrorCode errorCode() { return errorCode; }
}
