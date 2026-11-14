package ai.codecraft.modules.modelaccess.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.modelaccess.model.entity.ModelConfigurationEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelConnectionEntity;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelInvocationServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final ModelConfigurationRepository models = mock(ModelConfigurationRepository.class);
    private final ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
    private final ModelCredentialCipher cipher = mock(ModelCredentialCipher.class);
    private final ModelInvocationService service = new ModelInvocationService(identity, connections, models, cipher);

    @BeforeEach
    void setup() {
        when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, NOW.plusSeconds(60)));
        when(models.find("model")).thenReturn(new ModelConfigurationEntity("model", "connection", "owner", "Fixture", "fixture", 10000, 4096, "OFF", null, null, null, 4, 2, NOW, NOW, null));
        when(connections.find("connection")).thenReturn(new ModelConnectionEntity("connection", "owner", "Fixture", "https://example.com", "OPENAI_CHAT", "CUSTOM", "key", "nonce", "encrypted", 3, 2, NOW, NOW, null));
        when(cipher.decrypt(anyString(), anyString(), any())).thenReturn("fixture-secret");
    }

    @Test
    void displayVersionChangesDoNotInvalidateTheSelectedCallIdentity() {
        var resolved = service.resolve("session", new ModelInvocationReferenceDTO("connection", "model", 2, 2));
        assertEquals("fixture-secret", resolved.credential());
        assertEquals("4", resolved.model().version());
        assertEquals("2", resolved.model().configurationVersion());
        assertEquals("https://example.com", resolved.connection().baseUrl());
        assertFalse(resolved.toString().contains("fixture-secret"));
    }

    @Test
    void editedModelCannotBeResolvedUsingItsPreviousRevision() {
        assertEquals("MODEL_VERSION_CONFLICT", assertThrows(BusinessException.class,
            () -> service.resolve("session", new ModelInvocationReferenceDTO("connection", "model", 2, 1))).code());
        verify(cipher, never()).decrypt(anyString(), anyString(), any());
    }

    @Test
    void revocationAndForeignOwnershipPreventCredentialDecryption() {
        when(identity.authorizeMutation("revoked")).thenThrow(new BusinessException(IdentityErrorCode.UNAUTHENTICATED));
        when(identity.authorizeMutation("other")).thenReturn(new SessionAuthorizationDTO("other", "USER", false, 1, NOW.plusSeconds(60)));
        when(identity.authorizeMutation("admin")).thenReturn(new SessionAuthorizationDTO("owner", "ADMIN", false, 1, NOW.plusSeconds(60)));
        var reference = new ModelInvocationReferenceDTO("connection", "model", 2, 2);
        assertEquals("UNAUTHENTICATED", assertThrows(BusinessException.class, () -> service.resolve("revoked", reference)).code());
        assertEquals("MODEL_CONFIGURATION_NOT_FOUND", assertThrows(BusinessException.class, () -> service.resolve("other", reference)).code());
        assertEquals("FORBIDDEN", assertThrows(BusinessException.class, () -> service.resolve("admin", reference)).code());
        verify(cipher, never()).decrypt(anyString(), anyString(), any());
    }

    @Test
    void deletingParentPreventsFurtherCallsEvenIfItsModelRecordSurvives() {
        when(connections.find("connection")).thenReturn(new ModelConnectionEntity("connection", "owner", "Fixture", "https://example.com", "OPENAI_CHAT", "CUSTOM", null, null, null, 4, 3, NOW, NOW, NOW));
        assertEquals("MODEL_CONNECTION_NOT_FOUND", assertThrows(BusinessException.class,
            () -> service.resolve("session", new ModelInvocationReferenceDTO("connection", "model", 2, 2))).code());
        verify(cipher, never()).decrypt(anyString(), anyString(), any());
    }

    @Test
    void sameOwnerCannotCombineAModelWithAnotherConnectionsCredential() {
        when(connections.find("another")).thenReturn(new ModelConnectionEntity("another", "owner", "Another", "https://example.org", "OPENAI_CHAT", "CUSTOM", "key", "nonce", "encrypted", 3, 2, NOW, NOW, null));
        var failure = assertThrows(BusinessException.class, () -> service.resolve("session", new ModelInvocationReferenceDTO("another", "model", 2, 2)));
        assertEquals("MODEL_VERSION_CONFLICT", failure.code());
        verify(cipher, never()).decrypt(anyString(), anyString(), any());
    }

    @Test
    void editedConnectionCannotSilentlySupplyItsNewKeyToAnOldRevision() {
        var failure = assertThrows(BusinessException.class, () -> service.resolve("session", new ModelInvocationReferenceDTO("connection", "model", 1, 2)));
        assertEquals("MODEL_VERSION_CONFLICT", failure.code());
        verify(cipher, never()).decrypt(anyString(), anyString(), any());
    }
}
