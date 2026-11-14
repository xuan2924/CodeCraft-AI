package ai.codecraft.modules.copilot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.codecraft.common.exception.GlobalExceptionHandler;
import ai.codecraft.common.web.RequestBodyAdmission;
import ai.codecraft.modules.copilot.model.dto.CopilotStartRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.service.CopilotTaskService;
import ai.codecraft.modules.workspace.stream.TaskEventTransport;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CopilotHttpTest {
    private static final String ID = "10000000-0000-0000-0000-000000000001";
    private static final String PATH = "/api/exercises/" + ID + "/copilot/tasks";
    private static final String TOKEN = "s".repeat(43);
    private static final String HASH = IdentitySecrets.digest(TOKEN);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private CopilotTaskService tasks;
    private IdentityTransactionService identity;

    @BeforeAll void initialize() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Fixture.class); context.refresh();
        tasks = context.getBean(CopilotTaskService.class); identity = context.getBean(IdentityTransactionService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterAll void close() { context.close(); }
    @BeforeEach void authorize() {
        reset(tasks, identity);
        var user = mock(UserEntity.class); when(user.role()).thenReturn("USER");
        var auth = new AuthenticatedIdentityDTO(user, null, new SessionResponse("owner", "fixture", "USER", false, Instant.parse("2030-01-01T00:00:00Z")));
        when(identity.authenticate(any())).thenAnswer(call -> {
            if (!HASH.equals(call.getArgument(0))) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
            return auth;
        });
        when(identity.csrf(any(), any())).thenReturn("fixture-csrf");
        when(tasks.start(any(), any(), any(), any(), any())).thenReturn(new CopilotTaskResponse(ID, ID, "QUEUED", null, false, Instant.EPOCH, null));
    }
    @Test void admissionRequiresCsrfAndTheExpectedCookieOwner() throws Exception {
        mvc.perform(start().content("{\"text\":\"Inspect\",\"feeAcknowledged\":true}")).andExpect(status().isForbidden());
        mvc.perform(start().header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "old-owner")
            .content("{\"text\":\"Inspect\",\"feeAcknowledged\":true}")).andExpect(status().isUnauthorized());
        verify(tasks, never()).start(any(), any(), any(), any(), any());
        mvc.perform(start().header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "owner")
            .content("{\"text\":\"Inspect\",\"feeAcknowledged\":true}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.state").value("QUEUED"));
        verify(tasks).start(HASH, ID, "control", "original", new CopilotStartRequest("Inspect", true));
    }
    @Test void privateHistoryCannotBeReadByAnAnonymousOrStaleOwnerAndReadsNeverStartWork() throws Exception {
        String path = PATH + "/latest";
        mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "old-owner"))
            .andExpect(status().isUnauthorized());
        verify(tasks, never()).latest(any(), any());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(tasks).latest(HASH, ID); verify(tasks, never()).start(any(), any(), any(), any(), any());
    }
    @Test void malformedInputAndPersistenceFailuresAreSanitized() throws Exception {
        mvc.perform(start().header(IdentityHttp.CSRF_HEADER, "fixture-csrf").content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COPILOT_INVALID_REQUEST"));
        when(tasks.start(any(), any(), any(), any(), any())).thenThrow(new DataIntegrityViolationException("private-fixture-text"));
        var response = mvc.perform(start().header(IdentityHttp.CSRF_HEADER, "fixture-csrf").content("{\"text\":\"Inspect\",\"feeAcknowledged\":true}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("COPILOT_STORAGE_UNAVAILABLE")).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("private-fixture-text");
    }
    private MockHttpServletRequestBuilder start() {
        return post(PATH).servletPath(PATH).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .header("Idempotency-Key", "original").header("X-Operation-Credential", "control").contentType("application/json");
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({IdentitySecurity.class, CopilotController.class, CopilotErrors.class, GlobalExceptionHandler.class})
    static class Fixture {
        @Bean TaskEventTransport transport() { return mock(TaskEventTransport.class); }
        @Bean CopilotTaskService tasks() { return mock(CopilotTaskService.class); }
        @Bean IdentityTransactionService identity() { return mock(IdentityTransactionService.class); }
        @Bean IdentitySecrets secrets() { return mock(IdentitySecrets.class); }
        @Bean IdentitySettings settings() { return new IdentitySettings("https://app.example.test", "00".repeat(32), "", ""); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean RequestBodyAdmission bodies() { return new RequestBodyAdmission(List.of()); }
    }
}
