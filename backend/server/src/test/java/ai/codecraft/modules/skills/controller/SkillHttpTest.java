package ai.codecraft.modules.skills.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.codecraft.common.exception.GlobalExceptionHandler;
import ai.codecraft.common.web.RequestBodyAdmission;
import ai.codecraft.common.web.RequestBodyPolicy;
import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.identity.model.dto.AuthenticatedIdentityDTO;
import ai.codecraft.modules.identity.model.dto.SessionResponse;
import ai.codecraft.modules.identity.model.entity.UserEntity;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.identity.security.IdentitySecurity;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.skills.config.SkillJsonConfiguration;
import ai.codecraft.modules.skills.service.SkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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

class SkillHttpTest {
    @Test
    void importsPreserveDuplicateDetectionAndPrivateRoutesEnforceOwnerAndCsrf() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Fixture.class); context.refresh();
            var service = context.getBean(SkillService.class);
            var identity = context.getBean(IdentityTransactionService.class);
            String token = "s".repeat(43); String hash = IdentitySecrets.digest(token);
            var user = mock(UserEntity.class); when(user.role()).thenReturn("USER");
            var auth = new AuthenticatedIdentityDTO(user, null, new SessionResponse("owner", "fixture", "USER", false, Instant.parse("2030-01-01T00:00:00Z")));
            when(identity.authenticate(any())).thenAnswer(call -> {
                if (!hash.equals(call.getArgument(0))) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
                return auth;
            });
            when(identity.csrf(any(), any())).thenReturn("csrf");
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            mvc.perform(get("/api/skills").servletPath("/api/skills")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/skills").servletPath("/api/skills").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, token)).header("X-Codecraft-Owner", "other"))
                .andExpect(status().isUnauthorized());
            when(service.list(hash)).thenReturn(List.of());
            mvc.perform(get("/api/skills").servletPath("/api/skills").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, token)).header("X-Codecraft-Owner", "owner"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(post("/api/skills").servletPath("/api/skills").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, token)).header("Origin", "https://app.example.test")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
            for (String body : List.of("{\"formatVersion\":1,\"formatVersion\":1,\"files\":[]}", "{\"formatVersion\":1,\"files\":[],\"extra\":true}")) {
                mvc.perform(post("/api/skills").servletPath("/api/skills").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, token))
                    .header("Origin", "https://app.example.test").header(IdentityHttp.CSRF_HEADER, "csrf").header("Idempotency-Key", "import")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKILL_INVALID_PACKAGE"));
            }
            verify(service, never()).save(any(), any(), any(), any(), any());
            mvc.perform(post("/api/skills").servletPath("/api/skills").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, token))
                .header("Origin", "https://app.example.test").header(IdentityHttp.CSRF_HEADER, "csrf").header("Idempotency-Key", "large")
                .contentType("application/json").content("{\"formatVersion\":1,\"files\":[{\"path\":\"SKILL.md\",\"content\":\"" + "a".repeat(17000) + "\"}]}"))
                .andExpect(status().isOk());
            mvc.perform(post("/api/skills").servletPath("/api/skills").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, token))
                .header("Origin", "https://app.example.test").header(IdentityHttp.CSRF_HEADER, "csrf").header("Idempotency-Key", "too-large")
                .contentType("application/json").content("a".repeat(2097153))).andExpect(status().isPayloadTooLarge());
        }
    }
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({IdentitySecurity.class, SkillController.class, SkillErrors.class, SkillJsonConfiguration.class, GlobalExceptionHandler.class})
    static class Fixture {
        @Bean SkillService skills() { return mock(SkillService.class); }
        @Bean IdentityTransactionService identity() { return mock(IdentityTransactionService.class); }
        @Bean IdentitySecrets secrets() { return mock(IdentitySecrets.class); }
        @Bean IdentitySettings settings() { return new IdentitySettings("https://app.example.test", "00".repeat(32), "", ""); }
        @Bean("applicationJson") ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean RequestBodyAdmission bodies(List<RequestBodyPolicy> policies) { return new RequestBodyAdmission(policies); }
    }
}
