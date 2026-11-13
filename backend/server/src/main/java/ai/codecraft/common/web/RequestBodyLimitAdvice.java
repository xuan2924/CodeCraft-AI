package ai.codecraft.common.web;

import ai.codecraft.common.exception.ApiErrorWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Jackson may wrap the stream exception; preserve its exact failure semantics. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodyLimitAdvice {
    private final ObjectMapper json;
    public RequestBodyLimitAdvice(ObjectMapper json) { this.json = json; }
    @ExceptionHandler(RequestBodyLimitException.class)
    public void limit(RequestBodyLimitException failure, HttpServletResponse response) throws IOException {
        ApiErrorWriter.write(response, json, failure.errorCode());
    }
}
