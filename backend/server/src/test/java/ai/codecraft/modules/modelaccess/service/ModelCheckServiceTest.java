package ai.codecraft.modules.modelaccess.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import ai.codecraft.modules.modelaccess.model.dto.ModelCheckResponse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCheckProperties;
import java.time.Duration;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.entity.ModelConfigurationEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelConnectionEntity;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelCheckServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ModelCheckRepository checks = mock(ModelCheckRepository.class);
    private final ModelConfigurationRepository models = mock(ModelConfigurationRepository.class);
    private final ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final ModelCommandReceipts receipts = mock(ModelCommandReceipts.class);
    private final ModelCheckService service = new ModelCheckService(checks, models, connections, identity, receipts, Clock.fixed(NOW, ZoneOffset.UTC), new ModelCheckProperties(Duration.ofMinutes(5), 3, 65536));

    @BeforeEach
    void setup() {
        when(receipts.fingerprint(true)).thenReturn("fingerprint");
        when(receipts.complete(anyString(), anyString(), anyString(), anyString(), anyString(), any(ModelCheckResponse.class))).thenAnswer(call -> call.getArgument(5));
        when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, NOW.plusSeconds(60)));
        when(models.find("model")).thenReturn(new ModelConfigurationEntity("model", "connection", "owner", "Fixture", "fixture", 10000, 4096, "OFF", null, null, null, 4, 2, NOW, NOW, null));
        when(connections.find("connection")).thenReturn(new ModelConnectionEntity("connection", "owner", "Fixture", "https://example.com", "OPENAI_CHAT", "CUSTOM", "key", "nonce", "encrypted", 3, 2, NOW, NOW, null));
    }

    @Test
    void admittedGroupPinsConfigurationRevisionsRatherThanDisplayVersions() {
        var response = service.start("session", "model", "request", true);
        assertNotNull(response.id());
        assertEquals("QUEUED", response.status());
        assertEquals("2", response.connectionConfigurationVersion());
        assertEquals("2", response.modelConfigurationVersion());
        assertEquals(NOW, response.startedAt());
    }

    @Test
    void successfulRetryDoesNotCreateAnotherGroupEvenAfterConfigurationDeletion() {
        var previous = new ModelCheckResponse("previous", "model", "1", "1", "QUEUED", NOW.minusSeconds(10));
        when(receipts.replay("owner", "START_MODEL_CHECK", "model", "request", "fingerprint", ModelCheckResponse.class)).thenReturn(previous);
        when(models.find("model")).thenReturn(null);
        assertEquals(previous, service.start("session", "model", "request", true));
        verify(checks, never()).insert(any());
        verify(checks, never()).active(anyString());
    }

    @Test
    void administratorsAndForeignOwnersCannotStartPersonalChecks() {
        when(identity.authorizeMutation("admin")).thenReturn(new SessionAuthorizationDTO("admin", "ADMIN", false, 1, NOW.plusSeconds(60)));
        when(identity.authorizeMutation("other")).thenReturn(new SessionAuthorizationDTO("other", "USER", false, 1, NOW.plusSeconds(60)));
        assertEquals("FORBIDDEN", assertThrows(BusinessException.class, () -> service.start("admin", "model", "request", true)).code());
        assertEquals("MODEL_CONFIGURATION_NOT_FOUND", assertThrows(BusinessException.class, () -> service.start("other", "model", "request", true)).code());
        verify(checks, never()).insert(any());
        verify(receipts, never()).replay(eq("admin"), anyString(), anyString(), anyString(), any(), eq(ModelCheckResponse.class));
    }

    @Test
    void activeGroupOnAnotherConnectionBlocksNewPaidWorkForTheSameUser() {
        when(checks.active("owner")).thenReturn(true);
        var failure = assertThrows(BusinessException.class, () -> service.start("session", "model", "request", true));
        assertEquals("MODEL_CHECK_ALREADY_ACTIVE", failure.code());
        verify(checks, never()).insert(any());
    }

    @Test
    void threeStartsIncludingFailuresExhaustTheUserWindow() {
        when(checks.startsSince("owner", NOW.minusSeconds(300))).thenReturn(3);
        var failure = assertThrows(BusinessException.class, () -> service.start("session", "model", "request", true));
        assertEquals("MODEL_CHECK_RATE_LIMITED", failure.code());
        verify(checks, never()).insert(any());
    }

    @Test
    void missingFeeAcknowledgementCannotAdmitPotentiallyPaidWork() {
        var failure = assertThrows(BusinessException.class, () -> service.start("session", "model", "request", false));
        assertEquals("MODEL_CHECK_FEE_ACKNOWLEDGEMENT_REQUIRED", failure.code());
        verify(checks, never()).insert(any());
    }
}
