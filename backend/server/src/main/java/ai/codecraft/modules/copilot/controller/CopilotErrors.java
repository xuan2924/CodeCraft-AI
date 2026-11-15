package ai.codecraft.modules.copilot.controller;

import ai.codecraft.common.exception.ApiErrorWriter;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {CopilotController.class, CopilotApprovalController.class, CopilotSteerController.class, CopilotContextController.class})
public class CopilotErrors {
    private static final Logger LOG = LoggerFactory.getLogger(CopilotErrors.class);
    private final ObjectMapper json;
    public CopilotErrors(ObjectMapper json) { this.json = json; }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public void malformed(HttpServletResponse response) throws IOException {
        ApiErrorWriter.write(response, json, CopilotErrorCode.COPILOT_INVALID_REQUEST);
    }
    @ExceptionHandler(DataAccessException.class)
    public void database(DataAccessException failure, HttpServletResponse response) throws IOException {
        LOG.warn("Copilot persistence unavailable ({})", failure.getClass().getSimpleName());
        ApiErrorWriter.write(response, json, CopilotErrorCode.COPILOT_STORAGE_UNAVAILABLE);
    }
}
