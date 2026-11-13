package ai.codecraft.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestBodyAdmissionTest {
    @Test
    void wrappedParserFailureRemains413AndReleasesTheLargePermit() throws Exception {
        var gate = new RequestBodyAdmission(java.util.List.of());
        var json = new ObjectMapper();
        var mvc = MockMvcBuilders.standaloneSetup(new PayloadController())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .setControllerAdvice(new RequestBodyLimitAdvice(json), new MalformedAdvice())
            .addFilters((request, response, chain) -> {
                var unknown = new HttpServletRequestWrapper((HttpServletRequest) request) {
                    @Override public long getContentLengthLong() { return -1; }
                    @Override public int getContentLength() { return -1; }
                };
                try (var admitted = gate.admit(unknown, 32 * 1024)) { chain.doFilter(admitted.request(), response); }
            }).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/payload").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"" + "x".repeat(40000) + "\"}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        }
    }

    @RestController
    static class PayloadController {
        @PostMapping("/payload")
        public Map<String, String> payload(@RequestBody Map<String, String> value) { return value; }
    }
    @RestControllerAdvice
    @Order(Ordered.LOWEST_PRECEDENCE)
    static class MalformedAdvice {
        @ExceptionHandler(HttpMessageNotReadableException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public void malformed() { }
    }

    @Test
    void actualUnknownLengthBytesAreBoundedWithoutTrustingContentLength() {
        var request = new MockHttpServletRequest();
        request.setContent(new byte[(int) RequestBodyAdmission.SMALL_JSON_BYTES + 1]);
        var unknown = new HttpServletRequestWrapper(request) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        var gate = new RequestBodyAdmission(java.util.List.of());
        try (var admitted = gate.admit(unknown, RequestBodyAdmission.SMALL_JSON_BYTES)) {
            var failure = assertThrows(RequestBodyLimitException.class, () -> admitted.request().getInputStream().readAllBytes());
            assertEquals(413, failure.errorCode().status());
        }
    }

    @Test
    void largeInputIsNotQueuedAndThePermitIsReleasedExactlyOnceOnFailure() {
        var request = new MockHttpServletRequest();
        request.setContent(new byte[(int) RequestBodyAdmission.SMALL_JSON_BYTES + 1]);
        var gate = new RequestBodyAdmission(java.util.List.of());
        var first = gate.admit(request, RequestBodyAdmission.LARGE_JSON_BYTES);
        assertEquals(429, assertThrows(RequestBodyLimitException.class, () -> gate.admit(request, RequestBodyAdmission.LARGE_JSON_BYTES)).errorCode().status());
        first.close(); first.close();
        try (var second = gate.admit(request, RequestBodyAdmission.LARGE_JSON_BYTES)) {
            assertThrows(RequestBodyLimitException.class, () -> gate.admit(request, RequestBodyAdmission.LARGE_JSON_BYTES));
        }
        try (var admitted = gate.admit(request, RequestBodyAdmission.LARGE_JSON_BYTES)) {
            assertEquals(request.getContentLengthLong(), admitted.request().getInputStream().readAllBytes().length);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}
