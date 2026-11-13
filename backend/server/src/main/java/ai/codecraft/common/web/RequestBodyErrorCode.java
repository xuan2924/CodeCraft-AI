package ai.codecraft.common.web;

import ai.codecraft.common.exception.ErrorCode;

public enum RequestBodyErrorCode implements ErrorCode {
    REQUEST_TOO_LARGE(413), REQUEST_CAPACITY(429);
    private final int status;
    RequestBodyErrorCode(int status) { this.status = status; }
    @Override public int status() { return status; }
    @Override public String code() { return name(); }
}
