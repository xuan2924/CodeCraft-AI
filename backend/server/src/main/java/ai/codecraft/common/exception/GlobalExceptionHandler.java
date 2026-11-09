package ai.codecraft.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final ObjectMapper json;

    public GlobalExceptionHandler(ObjectMapper json) { this.json = json; }

    @ExceptionHandler(BusinessException.class)
    public void business(BusinessException failure, HttpServletResponse response) throws IOException {
        ApiErrorWriter.write(response, json, failure.errorCode());
    }
}
