package ai.codecraft.common.exception;

import ai.codecraft.common.model.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Shared JSON error rendering for MVC advice and filters that execute before MVC. */
public final class ApiErrorWriter {
    private ApiErrorWriter() { }

    public static void write(HttpServletResponse response, ObjectMapper json, ErrorCode code) throws IOException {
        response.setStatus(code.status());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), ApiResponse.failure(code.code()));
    }
}
