package ai.codecraft.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = IdentityController.class)
public class IdentityErrors {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityErrors.class);
    private final ObjectMapper json;

    public IdentityErrors(ObjectMapper json) { this.json = json; }

    @ExceptionHandler(IdentityFailure.class)
    public void identity(IdentityFailure failure, HttpServletResponse response) throws IOException {
        IdentityHttp.error(response, json, failure.status(), failure.code());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void malformed(HttpServletResponse response) throws IOException {
        IdentityHttp.error(response, json, 400, "INVALID_REQUEST");
    }

    @ExceptionHandler(DataAccessException.class)
    public void database(DataAccessException failure, HttpServletResponse response) throws IOException {
        LOG.error("Identity database unavailable ({})", failure.getClass().getSimpleName());
        IdentityHttp.error(response, json, 503, "IDENTITY_UNAVAILABLE");
    }
}
