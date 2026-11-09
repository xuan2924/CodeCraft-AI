package ai.codecraft.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {
    private enum FixtureCode implements ErrorCode {
        REJECTED;
        @Override public String code() { return name(); }
        @Override public int status() { return 409; }
    }

    @RestController
    static class FixtureController {
        @GetMapping("/fixture")
        public void rejected() { throw new BusinessException(FixtureCode.REJECTED); }
    }

    @Test
    void mvcAndPreMvcFiltersUseTheSameErrorContract() throws Exception {
        ObjectMapper json = new ObjectMapper();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
            .setControllerAdvice(new GlobalExceptionHandler(json)).build();
        MockHttpServletResponse response = mvc.perform(get("/fixture")).andExpect(status().isConflict())
            .andReturn().getResponse();
        MockHttpServletResponse filterResponse = new MockHttpServletResponse();
        ApiErrorWriter.write(filterResponse, json, FixtureCode.REJECTED);
        for (MockHttpServletResponse actual : new MockHttpServletResponse[] {response, filterResponse}) {
            assertThat(actual.getStatus()).isEqualTo(409);
            assertThat(actual.getHeaders("Cache-Control")).containsExactly("no-store");
            JsonNode body = json.readTree(actual.getContentAsByteArray());
            assertThat(body.path("code").asText()).isEqualTo("REJECTED");
            assertThat(body.path("data").isNull()).isTrue();
            assertThat(body.has("message")).isTrue();
            assertThat(body.has("stackTrace")).isFalse();
            assertThat(UUID.fromString(body.path("requestId").asText())).isNotNull();
        }
    }
}
