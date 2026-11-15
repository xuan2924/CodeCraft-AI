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
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeResponse;
import ai.codecraft.modules.copilot.service.CopilotApprovalModeService;
import ai.codecraft.modules.copilot.service.CopilotCompactionService;
import ai.codecraft.modules.copilot.service.CopilotUsageService;
import ai.codecraft.modules.copilot.service.CopilotSkillService;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionRequest;
import ai.codecraft.modules.copilot.service.CopilotSteerService;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.service.CopilotTaskService;
import ai.codecraft.modules.copilot.service.CopilotProposalService;
import ai.codecraft.modules.copilot.service.CopilotTestTools;
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
    private CopilotProposalService proposals;
    private CopilotTestTools tests;
    private CopilotApprovalModeService modes;
    private CopilotSteerService steers;
    private CopilotCompactionService compactions;
    private CopilotUsageService usage;
    private CopilotSkillService skills;
    private IdentityTransactionService identity;

    @BeforeAll void initialize() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Fixture.class); context.refresh();
        proposals = context.getBean(CopilotProposalService.class);
        tests = context.getBean(CopilotTestTools.class);
        modes = context.getBean(CopilotApprovalModeService.class);
        steers = context.getBean(CopilotSteerService.class);
        compactions = context.getBean(CopilotCompactionService.class);
        usage = context.getBean(CopilotUsageService.class);
        skills = context.getBean(CopilotSkillService.class);
        tasks = context.getBean(CopilotTaskService.class); identity = context.getBean(IdentityTransactionService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @Test
    void usageIsAnAuthenticatedNoStoreReadWithoutStartingOrCompactingAnything() throws Exception {
        String path = "/api/exercises/" + ID + "/copilot/usage";
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "owner"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(usage).current(HASH, ID);
        verify(compactions, never()).accept(any(), any(), any(), any(), any());
    }

    @Test
    void taskSkillCatalogIsPrivateReadOnlyAndOwnerBound() throws Exception {
        String path = "/api/copilot-tasks/" + ID + "/skills";
        mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "other"))
            .andExpect(status().isUnauthorized());
        verify(skills, never()).current(any(), any());
        when(skills.current(HASH, ID)).thenReturn(List.of());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "owner"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data").isEmpty());
        verify(skills).current(HASH, ID);
        verify(tasks, never()).start(any(), any(), any(), any(), any());
    }

    @AfterAll void close() { context.close(); }
    @BeforeEach void authorize() {
        reset(tasks, proposals, tests, modes, steers, compactions, usage, skills, identity);
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
    @Test void linkedTestsAreOwnerBoundNoStoreReadsWithoutExecutionSideEffects() throws Exception {
        String path = "/api/copilot-tasks/" + ID + "/tests";
        mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "old-owner"))
            .andExpect(status().isUnauthorized());
        verify(tests, never()).recent(any(), any());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(tests).recent(HASH, ID);
        verify(tests, never()).execute(any(), any(), any());
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
    @Test void proposalDecisionsUseCsrfAndOwnerBindingAndPrivateReadsNeverApprove() throws Exception {
        String path = "/api/copilot-proposals/" + ID;
        mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(proposals).detail(HASH, ID);
        String action = path + "/decision";
        mvc.perform(post(action).servletPath(action).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN))
            .contentType("application/json").content("{\"decision\":\"APPROVE\"}")).andExpect(status().isForbidden());
        mvc.perform(post(action).servletPath(action).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header(IdentityHttp.CSRF_HEADER, "fixture-csrf")
            .header("Origin", "https://app.example.test").header("X-Codecraft-Owner", "old-owner")
            .contentType("application/json").content("{\"decision\":\"APPROVE\"}")).andExpect(status().isUnauthorized());
        verify(proposals, never()).decide(any(), any(), any(), any(), any());
        mvc.perform(post(action).servletPath(action).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header(IdentityHttp.CSRF_HEADER, "fixture-csrf")
            .header("Origin", "https://app.example.test").header("X-Codecraft-Owner", "owner")
            .header("Idempotency-Key", "approval").header("X-Operation-Credential", "current")
            .contentType("application/json").content("{\"decision\":\"REJECT\",\"reason\":\"Keep API\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(proposals).decide(HASH, ID, "current", "approval", new CopilotProposalDecisionRequest("REJECT", "Keep API"));
    }

    @Test void approvalModeReadsDoNotSwitchAndMutationsRequireOwnerAndCsrf() throws Exception {
        String path = "/api/exercises/" + ID + "/copilot/approval-mode";
        when(modes.current(HASH, ID)).thenReturn(new CopilotApprovalModeResponse(ID, "ASK", "0", List.of()));
        mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "owner"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.mode").value("ASK"));
        verify(modes, never()).change(any(), any(), any(), any(), any());
        mvc.perform(post(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .contentType("application/json").content("{\"mode\":\"AUTO\",\"expectedVersion\":\"0\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "old-owner")
            .contentType("application/json").content("{\"mode\":\"AUTO\",\"expectedVersion\":\"0\"}"))
            .andExpect(status().isUnauthorized());
        verify(modes, never()).change(any(), any(), any(), any(), any());
        mvc.perform(post(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "owner").header("X-Operation-Credential", "current")
            .header("Idempotency-Key", "mode-original").contentType("application/json").content("{\"mode\":\"AUTO\",\"expectedVersion\":\"0\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(modes).change(HASH, ID, "current", "mode-original", new CopilotApprovalModeRequest("AUTO", "0"));
    }

    @Test void contextObservationCannotTriggerPaidWorkAndCommandsRequireOwnerAndCsrf() throws Exception {
        String base = "/api/exercises/" + ID + "/copilot/";
        mvc.perform(get(base + "context").servletPath(base + "context")).andExpect(status().isUnauthorized());
        mvc.perform(get(base + "context").servletPath(base + "context").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(compactions).current(HASH, ID); verify(compactions, never()).accept(any(), any(), any(), any(), any());
        String action = base + "compactions";
        mvc.perform(post(action).servletPath(action).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN))
            .contentType("application/json").content("{\"feeAcknowledged\":true}")).andExpect(status().isForbidden());
        mvc.perform(post(action).servletPath(action).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN))
            .header("Origin", "https://app.example.test").header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "old-owner")
            .contentType("application/json").content("{\"feeAcknowledged\":true}")).andExpect(status().isUnauthorized());
        verify(compactions, never()).accept(any(), any(), any(), any(), any());
        mvc.perform(post(action).servletPath(action).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN))
            .header("Origin", "https://app.example.test").header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "owner")
            .header("Idempotency-Key", "summary-original").header("X-Operation-Credential", "current")
            .contentType("application/json").content("{\"feeAcknowledged\":true}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"));
        verify(compactions).accept(HASH, ID, "current", "summary-original", new CopilotCompactionRequest(true));
    }

    @Test void steerIsAnOwnerBoundExplicitCommandAndReadsCannotDispatchIt() throws Exception {
        String path = "/api/copilot-tasks/" + ID + "/steers";
        mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("X-Codecraft-Owner", "owner"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(steers).list(HASH, ID, 0);
        verify(steers, never()).accept(any(), any(), any(), any(), any());
        mvc.perform(post(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .contentType("application/json").content("{\"text\":\"Direction\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "old-owner")
            .contentType("application/json").content("{\"text\":\"Direction\"}"))
            .andExpect(status().isUnauthorized());
        verify(steers, never()).accept(any(), any(), any(), any(), any());
        mvc.perform(post(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .header(IdentityHttp.CSRF_HEADER, "fixture-csrf").header("X-Codecraft-Owner", "owner").header("X-Operation-Credential", "current")
            .header("Idempotency-Key", "steer-original").contentType("application/json").content("{\"text\":\"Direction\"}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"));
        verify(steers).accept(HASH, ID, "current", "steer-original", new CopilotSteerRequest("Direction"));
        mvc.perform(get(path).servletPath(path).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).param("before", "invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COPILOT_INVALID_REQUEST"));
    }

    private MockHttpServletRequestBuilder start() {
        return post(PATH).servletPath(PATH).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://app.example.test")
            .header("Idempotency-Key", "original").header("X-Operation-Credential", "control").contentType("application/json");
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({IdentitySecurity.class, CopilotController.class, CopilotApprovalController.class, CopilotSteerController.class, CopilotContextController.class, CopilotSkillController.class, CopilotErrors.class, GlobalExceptionHandler.class})
    static class Fixture {
        @Bean TaskEventTransport transport() { return mock(TaskEventTransport.class); }
        @Bean CopilotTaskService tasks() { return mock(CopilotTaskService.class); }
        @Bean CopilotTestTools tests() { return mock(CopilotTestTools.class); }
        @Bean CopilotApprovalModeService modes() { return mock(CopilotApprovalModeService.class); }
        @Bean CopilotSteerService steers() { return mock(CopilotSteerService.class); }
        @Bean CopilotCompactionService compactions() { return mock(CopilotCompactionService.class); }
        @Bean CopilotSkillService skills() { return mock(CopilotSkillService.class); }
        @Bean CopilotUsageService usage() { return mock(CopilotUsageService.class); }
        @Bean CopilotProposalService proposals() { return mock(CopilotProposalService.class); }
        @Bean IdentityTransactionService identity() { return mock(IdentityTransactionService.class); }
        @Bean IdentitySecrets secrets() { return mock(IdentitySecrets.class); }
        @Bean IdentitySettings settings() { return new IdentitySettings("https://app.example.test", "00".repeat(32), "", ""); }
        @Bean({"json", "skillJson"}) ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean RequestBodyAdmission bodies() { return new RequestBodyAdmission(List.of()); }
    }
}
