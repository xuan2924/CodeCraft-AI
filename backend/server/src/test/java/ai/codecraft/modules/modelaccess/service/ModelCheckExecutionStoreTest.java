package ai.codecraft.modules.modelaccess.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.config.ModelCheckProperties;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckAuthorityEntity;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationResolutionDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckReuseEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckRequestEntity;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelCheckExecutionStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ModelCheckRepository checks = mock(ModelCheckRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final ModelInvocationService invocations = mock(ModelInvocationService.class);
    private final Clock clock = mock(Clock.class);
    private final ModelCheckExecutionStore store = new ModelCheckExecutionStore(checks, identity, invocations,
        new ModelCallProperties(Duration.ofSeconds(120), Duration.ofSeconds(10), 8, 8388608, 65536, 262144),
        new ModelCheckProperties(Duration.ofMinutes(5), 3, 65536), clock,
        mock(ModelUsageAccounting.class));

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(checks.find("check")).thenReturn(new ModelCheckEntity("check", "owner", "connection", "model", 1, 1, "RUNNING", NOW));
        when(checks.authority("check")).thenReturn(new ModelCheckAuthorityEntity("check", "owner", "session", 1, NOW.plusSeconds(300), "worker"));
        when(identity.tryAuthorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, NOW.plusSeconds(600)));
    }

    @Test
    void responseAtThePersistedDeadlineCannotBecomeASuccessfulCheck() {
        when(checks.request("request")).thenReturn(new ModelCheckRequestEntity("request", "check", 1, "worker", "DISPATCHED", NOW.minusSeconds(120), NOW, 0));
        when(checks.completeRequest(anyString(), anyString(), anyInt(), any(), any())).thenReturn(1);
        assertFalse(store.complete("request", "worker", 16, null));
        verify(checks).transition("check", "INTERRUPTED", "MODEL_REQUEST_TIMEOUT");
    }

    @Test
    void revokedOriginalSessionCannotPublishALateProviderSuccess() {
        when(checks.request("request")).thenReturn(new ModelCheckRequestEntity("request", "check", 1, "worker", "DISPATCHED", NOW, NOW.plusSeconds(120), 0));
        when(identity.tryAuthorizeMutation("session")).thenReturn(null);
        when(checks.completeRequest(anyString(), anyString(), anyInt(), any(), any())).thenReturn(1);
        assertFalse(store.complete("request", "worker", 16, null));
        verify(checks).transition("check", "INTERRUPTED", "MODEL_CALL_CANCELLED");
    }

    @Test
    void oldRevisionResponseCannotCertifyTheEditedConfiguration() {
        when(checks.request("request")).thenReturn(new ModelCheckRequestEntity("request", "check", 1, "worker", "DISPATCHED", NOW, NOW.plusSeconds(120), 0));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, ModelAccessErrorCode.MODEL_VERSION_CONFLICT));
        when(checks.completeRequest(anyString(), anyString(), anyInt(), any(), any())).thenReturn(1);
        assertFalse(store.complete("request", "worker", 16, null));
        verify(checks).transition("check", "SUPERSEDED", "MODEL_VERSION_CONFLICT");
    }

    @Test
    void individuallySmallRepliesCannotExceedTheGroupsCombinedDecodedBudget() {
        when(checks.request("request")).thenReturn(new ModelCheckRequestEntity("request", "check", 3, "worker", "DISPATCHED", NOW, NOW.plusSeconds(120), 0));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        when(checks.decodedBytes("check")).thenReturn(65530);
        when(checks.completeRequest(anyString(), anyString(), anyInt(), any(), any())).thenReturn(1);
        assertFalse(store.complete("request", "worker", 16, null));
        verify(checks).transition("check", "FAILED", "MODEL_OUTPUT_LIMIT_EXCEEDED");
    }

    @Test
    void recoveryAfterRestartInterruptsExpiredUncertainRequestsWithoutReissuingThem() {
        when(checks.latest("check")).thenReturn(new ModelCheckRequestEntity("request", "check", 1, "worker", "DISPATCHED", NOW.minusSeconds(120), NOW, 0));
        when(checks.transition("check", "INTERRUPTED", "MODEL_CALL_CANCELLED")).thenReturn(1);
        assertTrue(store.recoverExpired("check"));
        verify(checks).completeRequest("request", "INTERRUPTED", 0, NOW, "MODEL_REQUEST_TIMEOUT");
        verify(checks, never()).insertRequest(any());
    }

    @Test
    void recoveryDoesNotAssumeAStillAuthorizedRequestHasFinished() {
        when(checks.latest("check")).thenReturn(new ModelCheckRequestEntity("request", "check", 1, "worker", "DISPATCHED", NOW, NOW.plusSeconds(120), 0));
        assertFalse(store.recoverExpired("check"));
        verify(checks, never()).transition(anyString(), anyString(), any());
    }

    @Test
    void successfulCompletionAllowsOnlyTheNextPhaseOnTheSameWorker() {
        when(checks.latest("check")).thenReturn(new ModelCheckRequestEntity("previous", "check", 1, "worker", "SUCCEEDED", NOW, NOW.plusSeconds(120), 20));
        when(checks.decodedBytes("check")).thenReturn(20);
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        assertNull(store.begin("check", 1, "worker"));
        assertNull(store.begin("check", 3, "worker"));
        assertNull(store.begin("check", 2, "other-worker"));
        var dispatch = store.begin("check", 2, "worker");
        assertNotNull(dispatch);
        assertEquals(65516, dispatch.remainingDecodedBytes());
        assertEquals(NOW.plusSeconds(120), dispatch.notAfter());
    }

    @Test
    void originalAuthorityExpiryIsNotExtendedByALaterSessionRefresh() {
        when(checks.authority("check")).thenReturn(new ModelCheckAuthorityEntity("check", "owner", "session", 1, NOW.plusSeconds(10), "worker"));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        assertEquals(NOW.plusSeconds(10), store.begin("check", 1, "worker").notAfter());
    }

    @Test
    void localResolutionDelayCannotCreateAnAlreadyExpiredDispatchPermit() {
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(120));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        assertNull(store.begin("check", 1, "worker"));
        verify(checks, never()).insertRequest(any());
        verify(checks).transition("check", "INTERRUPTED", "MODEL_REQUEST_TIMEOUT");
    }

    @Test
    void resultValidationCannotRunPastThePersistedDeadlineAndStillPublishSuccess() {
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(120));
        when(checks.request("request")).thenReturn(new ModelCheckRequestEntity("request", "check", 1, "worker", "DISPATCHED", NOW, NOW.plusSeconds(120), 0));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        when(checks.completeRequest(anyString(), anyString(), anyInt(), any(), any())).thenReturn(1);
        assertFalse(store.complete("request", "worker", 16, null));
        verify(checks).transition("check", "INTERRUPTED", "MODEL_REQUEST_TIMEOUT");
    }

    @Test
    void continuationCannotRefreshAnExpiredSuccessfulPredecessorsDeadline() {
        when(checks.latest("check")).thenReturn(new ModelCheckRequestEntity("previous", "check", 1, "worker", "SUCCEEDED", NOW.minusSeconds(120), NOW, 20));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        assertNull(store.begin("check", 2, "worker"));
        verify(checks, never()).insertRequest(any());
        verify(checks).transition("check", "INTERRUPTED", "MODEL_CALL_CANCELLED");
    }

    @Test
    void continuationResolutionCannotCrossThePredecessorsDeadline() {
        when(checks.latest("check")).thenReturn(new ModelCheckRequestEntity("previous", "check", 1, "worker", "SUCCEEDED", NOW.minusSeconds(110), NOW.plusSeconds(10), 20));
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(10));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        assertNull(store.begin("check", 2, "worker"));
        verify(checks, never()).insertRequest(any());
        verify(checks).transition("check", "INTERRUPTED", "MODEL_CALL_CANCELLED");
    }

    @Test
    void cachedBasicResultSkipsTheFirstPaidRequestButDoesNotSkipTheToolRoundtrip() {
        when(checks.reuse("check")).thenReturn(new ModelCheckReuseEntity("check", "original-basic", null));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        assertEquals(2, store.firstOrdinal("check"));
        assertNull(store.begin("check", 1, "worker"));
        assertNotNull(store.begin("check", 2, "worker"));
        assertNull(store.begin("check", 3, "worker"));
    }

    @Test
    void cachedToolRoundtripLetsTheNewBasicResultFinishWithoutTwoMorePaidRequests() {
        when(checks.reuse("check")).thenReturn(new ModelCheckReuseEntity("check", null, "original-tool"));
        when(checks.request("request")).thenReturn(new ModelCheckRequestEntity("request", "check", 1, "worker", "DISPATCHED", NOW, NOW.plusSeconds(120), 0));
        when(invocations.tryResolve(anyString(), any())).thenReturn(new ModelInvocationResolutionDTO(null, null));
        when(checks.completeRequest(anyString(), anyString(), anyInt(), any(), any())).thenReturn(1);
        assertTrue(store.complete("request", "worker", 100, null));
        verify(checks).transition("check", "SUCCEEDED", null);
    }

    @Test
    void unknownDispatchedRequestIsNeverReissuedOrSkippedToTheNextPhase() {
        when(checks.latest("check")).thenReturn(new ModelCheckRequestEntity("request", "check", 1, "worker", "DISPATCHED", NOW, NOW.plusSeconds(120), 0));
        assertNull(store.begin("check", 1, "worker"));
        assertNull(store.begin("check", 2, "worker"));
        verify(checks, never()).insertRequest(any());
    }
}
