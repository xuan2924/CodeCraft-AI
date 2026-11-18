package ai.codecraft.modules.workspace.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.codecraft.common.exception.GlobalExceptionHandler;
import ai.codecraft.common.web.RequestBodyAdmission;
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
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.service.CommandTaskService;
import ai.codecraft.modules.workspace.service.WorkspaceHistoryService;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreRequest;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
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
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandHttpTest {
    private static final String ID = "10000000-0000-0000-0000-000000000001";
    private static final String PATH = "/api/exercises/" + ID + "/commands";
    private static final String TOKEN = "s".repeat(43);
    private static final String HASH = IdentitySecrets.digest(TOKEN);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private CommandTaskService commands;
    private WorkspaceHistoryService history;
    private IdentityTransactionService identity;

    @BeforeAll void initialize() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Fixture.class); context.refresh();
        commands = context.getBean(CommandTaskService.class); identity = context.getBean(IdentityTransactionService.class);
        history = context.getBean(WorkspaceHistoryService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterAll void close() { context.close(); }
    @BeforeEach void authorize() {
        reset(commands, history, identity);
        var user = mock(UserEntity.class); when(user.role()).thenReturn("USER");
        var auth = new AuthenticatedIdentityDTO(user, null, new SessionResponse("owner", "fixture", "USER", false, Instant.parse("2030-01-01T00:00:00Z")));
        when(identity.authenticate(any())).thenAnswer(call -> {
            if (!HASH.equals(call.getArgument(0))) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
            return auth;
        });
        when(identity.csrf(any(), any())).thenReturn("fixture-csrf");
    }

    @Test void adminProblemsRejectUsersAndInvalidArtifacts() throws Exception {
        String path = "/api/admin/problems";
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN))).andExpect(status().isForbidden());
        when(identity.authenticate(HASH).user().role()).thenReturn("ADMIN");
        mvc.perform(mutation(path + "/validate").header(IdentityHttp.CSRF_HEADER, "fixture-csrf").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.valid").value(false));
    }

    @Test void startAndStopRequireCsrfAndCurrentCookieOwner() throws Exception {
        String body = "{\"command\":\"exit 7\",\"directory\":\".\",\"expectedRevision\":\"1\"}";
        mvc.perform(mutation(PATH).content(body)).andExpect(status().isForbidden());
        mvc.perform(mutation(PATH).content(body).header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "old-owner"))
            .andExpect(status().isUnauthorized());
        verify(commands, never()).start(any(), any(), any(), any(), any());
        mvc.perform(mutation(PATH).content(body).header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "owner"))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"));
        verify(commands).start(HASH, ID, "original", "control", new CommandStartRequest("exit 7", ".", "1"));
        mvc.perform(mutation(PATH + "/" + ID + "/stop")).andExpect(status().isForbidden());
        verify(commands, never()).stop(any(), any(), any(), any());
    }

    @Test void historyDetailAndDiffArePrivateNoStoreReads() throws Exception {
        for (String path : List.of(PATH, PATH + "/" + ID, PATH + "/" + ID + "/changes")) {
            mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "other"))
                .andExpect(status().isUnauthorized());
            mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "owner"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        verify(commands).history(HASH, ID, null); verify(commands).query(HASH, ID, ID); verify(commands).changes(HASH, ID, ID);
        verify(commands, never()).start(any(), any(), any(), any(), any());
    }

    @Test void codeHistoryAndPreviewArePrivateReadOnlyNoStoreRoutes() throws Exception {
        String historyPath = "/api/exercises/" + ID + "/history";
        for (String path : List.of(historyPath, historyPath + "/" + ID + "/preview")) {
            mvc.perform(get(path).servletPath(path).param("revision", "1")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).servletPath(path).param("revision", "1").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN))
                .header("X-Codecraft-Owner", "other")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).servletPath(path).param("revision", "1").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN))
                .header("X-Codecraft-Owner", "owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        verify(history).recent(HASH, ID, 0);
        verify(history).preview(HASH, ID, ID, "1", "DIFF", null);
        verify(history, never()).restore(any(), any(), any(), any(), any());
    }

    @Test void codeRestoreRequiresCsrfAndPreservesTheOriginalIntentHeaders() throws Exception {
        String path = "/api/exercises/" + ID + "/history/restore";
        var input = new WorkspaceRestoreRequest(ID, "UNDO", "1", null, "a".repeat(64));
        when(history.restore(HASH, ID, "original", "control", input)).thenReturn(new WorkspaceRestoreResponse(ID, "original", input,
            new WorkspaceOperationResponse(ID, ID, "SUCCEEDED", "/api/operations/" + ID, "2", true, null), null));
        String body = "{\"changeId\":\"" + ID + "\",\"mode\":\"UNDO\",\"expectedRevision\":\"1\",\"previewFingerprint\":\"" + "a".repeat(64) + "\"}";
        mvc.perform(mutation(path).content(body)).andExpect(status().isForbidden());
        mvc.perform(mutation(path).content(body).header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "other"))
            .andExpect(status().isUnauthorized());
        verify(history, never()).restore(any(), any(), any(), any(), any());
        mvc.perform(mutation(path).content(body).header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "owner"))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"));
        verify(history).restore(HASH, ID, "original", "control", new WorkspaceRestoreRequest(ID, "UNDO", "1", null, "a".repeat(64)));
    }

    private MockHttpServletRequestBuilder mutation(String path) {
        return post(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .header("Idempotency-Key", "original").header("X-Operation-Credential", "control").contentType("application/json");
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({IdentitySecurity.class, AdminProblemController.class, CommandTaskController.class, WorkspaceHistoryController.class, GlobalExceptionHandler.class})
    static class Fixture {
        @Bean ai.codecraft.modules.workspace.service.AdminProblemStore adminStore() { return mock(ai.codecraft.modules.workspace.service.AdminProblemStore.class); }
        @Bean ai.codecraft.modules.workspace.service.ProblemService problems(ObjectMapper json) {
            return new ai.codecraft.modules.workspace.service.ProblemService(null, new ai.codecraft.modules.workspace.service.ProblemPublicationReader(json));
        }
        @Bean CommandTaskService commands() { return mock(CommandTaskService.class); }
        @Bean WorkspaceHistoryService history() { return mock(WorkspaceHistoryService.class); }
        @Bean IdentityTransactionService identity() { return mock(IdentityTransactionService.class); }
        @Bean IdentitySecrets secrets() { return mock(IdentitySecrets.class); }
        @Bean IdentitySettings settings() { return new IdentitySettings("https://app.example.test", "00".repeat(32), "", ""); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean RequestBodyAdmission bodies() { return new RequestBodyAdmission(List.of()); }
    }
}
