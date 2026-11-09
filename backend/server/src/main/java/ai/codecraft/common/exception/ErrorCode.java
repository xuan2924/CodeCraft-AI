package ai.codecraft.common.exception;

/** Modules supply their own stable error codes without coupling common infrastructure to business types. */
public interface ErrorCode {
    String code();
    int status();
}
