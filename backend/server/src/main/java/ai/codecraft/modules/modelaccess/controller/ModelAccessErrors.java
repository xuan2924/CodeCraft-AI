package ai.codecraft.modules.modelaccess.controller;

import ai.codecraft.common.exception.ApiErrorWriter;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
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

@RestControllerAdvice(assignableTypes = {ModelConnectionController.class, ModelConfigurationController.class})
public class ModelAccessErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ModelAccessErrors.class);
    private final ObjectMapper json;
    public ModelAccessErrors(ObjectMapper json) { this.json = json; }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public void malformed(HttpServletResponse response) throws IOException { ApiErrorWriter.write(response, json, ModelAccessErrorCode.MODEL_INVALID_REQUEST); }

    @ExceptionHandler(DataAccessException.class)
    public void database(DataAccessException failure, HttpServletResponse response) throws IOException {
        LOG.warn("Model database unavailable ({})", failure.getClass().getSimpleName());
        ApiErrorWriter.write(response, json, ModelAccessErrorCode.MODEL_STORAGE_UNAVAILABLE);
    }
}
