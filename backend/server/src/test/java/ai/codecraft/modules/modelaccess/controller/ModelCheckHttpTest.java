package ai.codecraft.modules.modelaccess.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import ai.codecraft.common.exception.BusinessException;
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
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckStatusResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckItemResponse;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.service.ModelCheckService;
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

/** Actual MVC and application security filters, with only transactional business collaborators replaced. No worker/provider is loaded. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelCheckHttpTest {
    private static final String MODEL = "10000000-0000-0000-0000-000000000001";
    private static final String CHECK = "10000000-0000-0000-0000-000000000002";
    private static final String PATH = "/api/model-configurations/" + MODEL + "/checks";
    private static final String TOKEN = "s".repeat(43);
    private static final String HASH = IdentitySecrets.digest(TOKEN);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ModelCheckService checks;
    private IdentityTransactionService identity;

    @BeforeAll
    void initialize() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(Fixture.class); context.refresh();
        checks = context.getBean(ModelCheckService.class); identity = context.getBean(IdentityTransactionService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterAll void close() { context.close(); }

    @BeforeEach
    void authorizeFixture() {
        reset(checks, identity);
        var user = mock(UserEntity.class);
        when(user.role()).thenReturn("USER");
        var auth = new AuthenticatedIdentityDTO(user, null, new SessionResponse("owner", "fixture", "USER", false, Instant.parse("2030-01-01T00:00:00Z")));
        when(identity.authenticate(any())).thenAnswer(call -> {
            if (!HASH.equals(call.getArgument(0))) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
            return auth;
        });
        when(identity.csrf(any(), any())).thenReturn("fixture-csrf");
        when(checks.start(HASH, MODEL, "request", true)).thenReturn(new ModelCheckResponse(CHECK, MODEL, "1", "1", "QUEUED", Instant.EPOCH));
        when(checks.start(HASH, MODEL, "request", false)).thenThrow(new BusinessException(ModelAccessErrorCode.MODEL_CHECK_FEE_ACKNOWLEDGEMENT_REQUIRED));
    }

    @Test
    void acceptedRequestCarriesOnlyTheAuthenticatedSessionHashAndExplicitFeeAcknowledgement() throws Exception {
        mvc.perform(start().header(IdentityHttp.CSRF_HEADER, "fixture-csrf").content("{\"feeAcknowledged\":true}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/model-checks/" + CHECK))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.id").value(CHECK));
        verify(checks).start(HASH, MODEL, "request", true);
    }

    @Test
    void missingCsrfAndForeignOriginCannotReachPaidWorkAdmission() throws Exception {
        mvc.perform(start().content("{\"feeAcknowledged\":true}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post(PATH).servletPath(PATH).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)).header("Origin", "https://untrusted.example")
            .header(IdentityHttp.CSRF_HEADER, "fixture-csrf").contentType("application/json").content("{\"feeAcknowledged\":true}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        verify(checks, never()).start(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void omittedFeeAcknowledgementIsFalseAndRejected() throws Exception {
        mvc.perform(start().header(IdentityHttp.CSRF_HEADER, "fixture-csrf").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_CHECK_FEE_ACKNOWLEDGEMENT_REQUIRED"));
    }

    @Test
    void privateReadRequiresAuthenticationAndMalformedIdsAreSafelyRejected() throws Exception {
        String path = "/api/model-checks/" + CHECK;
        mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/model-checks/not-a-uuid").servletPath("/api/model-checks/not-a-uuid").cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_INVALID_REQUEST"));
        verify(checks, never()).status(any(), anyString());
    }

    @Test
    void statusAndLatestRoutesReadIndependentResultsWithoutDispatching() throws Exception {
        var pending = new ModelCheckItemResponse("PENDING", null, null, false, false, null);
        var response = new ModelCheckStatusResponse(new ModelCheckResponse(CHECK, MODEL, "1", "1", "QUEUED", Instant.EPOCH), null, true, pending, pending);
        when(checks.status(HASH, CHECK)).thenReturn(response);
        when(checks.latest(HASH, MODEL)).thenReturn(response);
        String detail = "/api/model-checks/" + CHECK;
        mvc.perform(get(detail).servletPath(detail).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.basic.valid").value(false));
        String latest = PATH + "/latest";
        mvc.perform(get(latest).servletPath(latest).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.tool.status").value("PENDING"));
        verify(checks, never()).start(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void malformedJsonAndSecretBearingDatabaseErrorsNeverExposeRawMessages() throws Exception {
        mvc.perform(start().header(IdentityHttp.CSRF_HEADER, "fixture-csrf").content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_INVALID_REQUEST"));
        when(checks.start(HASH, MODEL, "request", true)).thenThrow(new DataIntegrityViolationException("fixture-private-database-row"));
        var result = mvc.perform(start().header(IdentityHttp.CSRF_HEADER, "fixture-csrf").content("{\"feeAcknowledged\":true}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_STORAGE_UNAVAILABLE")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("fixture-private-database-row"));
    }

    private MockHttpServletRequestBuilder start() {
        return post(PATH).servletPath(PATH).cookie(new Cookie(IdentityHttp.SESSION_COOKIE, TOKEN))
            .header("Origin", "https://app.example.test").header("Idempotency-Key", "request").contentType("application/json");
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({IdentitySecurity.class, ModelCheckController.class, ModelAccessErrors.class, GlobalExceptionHandler.class})
    static class Fixture {
        @Bean ModelCheckService checks() { return mock(ModelCheckService.class); }
        @Bean IdentityTransactionService identity() { return mock(IdentityTransactionService.class); }
        @Bean IdentitySecrets secrets() { return mock(IdentitySecrets.class); }
        @Bean IdentitySettings settings() { return new IdentitySettings("https://app.example.test", "00".repeat(32), "", ""); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean RequestBodyAdmission bodies() { return new RequestBodyAdmission(List.of()); }
    }
}
