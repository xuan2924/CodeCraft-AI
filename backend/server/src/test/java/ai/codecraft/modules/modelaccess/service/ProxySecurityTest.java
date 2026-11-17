package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ai.codecraft.common.web.RequestBodyAdmission;
import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.identity.security.IdentitySecurity;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.controller.LlmProxyController;
import ai.codecraft.modules.modelaccess.security.ProxyTokens;
import ai.codecraft.modules.workspace.config.WorkspaceProperties;
import ai.codecraft.modules.workspace.service.TerminalModelAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class ProxySecurityTest {
    private static final String ID = "10000000-0000-0000-0000-000000000001";
    private static final String PATH = "/internal/llm-proxy/openai/v1/responses";
    @Test void dedicatedChainAuthenticatesAllTokenHeadersWithoutWeakeningApiCsrf() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Fixture.class); context.refresh();
            var authority = context.getBean(TerminalModelAccessService.class);
            when(authority.authorize(ID, ID, 7)).thenReturn(new TerminalModelAccessService.Authority(ID, ID, ID, 7));
            var tokens = context.getBean(ProxyTokens.class);
            String token = tokens.issue(ID, ID, 7);
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            mvc.perform(post(PATH).servletPath(PATH).content("{}")).andExpect(status().isForbidden());
            mvc.perform(post(PATH).servletPath(PATH).header("x-api-key", "bad").content("{}")).andExpect(status().isForbidden());
            for (String header : List.of("Authorization", "x-api-key", "X-Codecraft-Proxy-Token")) {
                mvc.perform(post(PATH).servletPath(PATH).header(header, header.equals("Authorization") ? "Bearer " + token : token)
                    .contentType("application/json").content("{}")).andExpect(status().isOk());
            }
            mvc.perform(post("/api/exercises/" + ID + "/terminal/session").servletPath("/api/exercises/" + ID + "/terminal/session")
                .header("x-api-key", token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mvc.perform(post("/internal/llm-grants").servletPath("/internal/llm-grants").header("x-api-key", token).content("{}")).andExpect(status().isForbidden());
            var grant = mvc.perform(post("/internal/llm-grants").servletPath("/internal/llm-grants").header("X-Codecraft-Internal-Key", "a".repeat(64))
                .contentType("application/json").content("{\"task\":\"" + ID + "\",\"exercise\":\"" + ID + "\",\"epoch\":7}"))
                .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(grant.getContentAsString()).contains("token").doesNotContain("a".repeat(64));
            for (String state : List.of("PAUSED", "ENDED", "EPOCH_CHANGED", "DATABASE_UNAVAILABLE")) {
                org.mockito.Mockito.doThrow(new IllegalStateException(state)).when(authority).authorize(ID, ID, 7);
                mvc.perform(post(PATH).servletPath(PATH).header("x-api-key", token).content("{}")).andExpect(status().isForbidden());
            }
        }
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({IdentitySecurity.class, LlmProxyController.class})
    static class Fixture {
        @Bean ProxyTokens tokens(TerminalModelAccessService authority) { return new ProxyTokens(Clock.systemUTC(), authority); }
        @Bean TerminalModelAccessService authority() { return mock(TerminalModelAccessService.class); }
        @Bean LlmProxyService proxy() { return mock(LlmProxyService.class); }
        @Bean IdentityTransactionService identity() { return mock(IdentityTransactionService.class); }
        @Bean IdentitySecrets secrets() { return mock(IdentitySecrets.class); }
        @Bean IdentitySettings settings() { return new IdentitySettings("https://app.example.test", "00".repeat(32), "", ""); }
        @Bean WorkspaceProperties workspace() {
            var properties = mock(WorkspaceProperties.class); when(properties.rpcKey()).thenReturn("a".repeat(64)); return properties;
        }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean RequestBodyAdmission bodies() { return new RequestBodyAdmission(List.of()); }
    }
}
