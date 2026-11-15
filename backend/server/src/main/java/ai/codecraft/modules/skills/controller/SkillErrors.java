package ai.codecraft.modules.skills.controller;

import ai.codecraft.common.exception.ApiErrorWriter;
import ai.codecraft.modules.skills.model.exception.SkillErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = SkillController.class)
public class SkillErrors {
    private static final Logger LOG = LoggerFactory.getLogger(SkillErrors.class);
    private final ObjectMapper json;
    public SkillErrors(ObjectMapper json) { this.json = json; }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class})
    public void malformed(HttpServletResponse response) throws IOException { ApiErrorWriter.write(response, json, SkillErrorCode.SKILL_INVALID_PACKAGE); }
    @ExceptionHandler(DataAccessException.class)
    public void database(DataAccessException failure, HttpServletResponse response) throws IOException {
        LOG.warn("Skill storage unavailable ({})", failure.getClass().getSimpleName());
        ApiErrorWriter.write(response, json, SkillErrorCode.SKILL_STORAGE_UNAVAILABLE);
    }
}
