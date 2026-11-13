package ai.codecraft.modules.modelaccess.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.entity.ModelCommandEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelConnectionEntity;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelConnectionServiceTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final ModelConnectionRepository repository = mock(ModelConnectionRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final IdentitySecrets secrets = mock(IdentitySecrets.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<ModelConnectionEntity> stored = new AtomicReference<>();
    private final ModelConnectionRequest input = new ModelConnectionRequest("DeepSeek", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT,
        ModelProviderPreset.DEEPSEEK, "fixture-only-model-key", null);

    private ModelConnectionService service(boolean configured) {
        var own = new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60));
        when(identity.authorizeMutation("session")).thenReturn(own);
        when(identity.authorizePrivateDelivery("session")).thenReturn(own);
        var other = new SessionAuthorizationDTO("another", "USER", false, 1, now.plusSeconds(60));
        when(identity.authorizePrivateDelivery("other")).thenReturn(other);
        when(identity.authorizeMutation("other")).thenReturn(other);
        doAnswer(call -> { stored.set(call.getArgument(0)); return null; }).when(repository).insert(any());
        when(repository.find(anyString())).thenAnswer(call -> stored.get());
        when(repository.update(any(), anyLong())).thenAnswer(call -> {
            if (stored.get().version() != call.<Long>getArgument(1)) return 0;
            stored.set(call.getArgument(0)); return 1;
        });
        when(identity.authorizePrivateDelivery("admin")).thenReturn(new SessionAuthorizationDTO("owner", "ADMIN", false, 1, now.plusSeconds(60)));
        when(secrets.fingerprint(anyString())).thenAnswer(call -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(call.<String>getArgument(0).getBytes(StandardCharsets.UTF_8))));
        var properties = configured ? new ModelSecurityProperties("key", "key:" + Base64.getEncoder().encodeToString(new byte[32])) : new ModelSecurityProperties("", "");
        return new ModelConnectionService(repository, identity, new ModelCredentialCipher(properties), new ModelConnectionValidator(),
            new ModelCommandReceipts(repository, secrets, json, Clock.fixed(now, ZoneOffset.UTC)), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void privateConnectionCreationStoresCiphertextAndDoesNotExposeKeysToOwnerOrAdministrator() throws Exception {
        var service = service(true);
        var created = service.create("session", "first", input);
        assertTrue(created.keyConfigured());
        assertFalse(json.writeValueAsString(created).contains(input.apiKey()));
        assertFalse(stored.get().credentialCiphertext().contains(input.apiKey()));
        assertFalse(input.toString().contains(input.apiKey()));
        assertEquals(created, service.connection("session", created.id()));
        assertEquals("MODEL_CONNECTION_NOT_FOUND", assertThrows(BusinessException.class, () -> service.connection("other", created.id())).code());
        assertEquals("FORBIDDEN", assertThrows(BusinessException.class, () -> service.connection("admin", created.id())).code());
    }

    @Test
    void originalRequestReceiptReplaysWithoutCreatingAnotherEncryptedConnection() {
        var service = service(true);
        var receipt = new AtomicReference<ModelCommandEntity>();
        when(repository.command("owner", "CREATE_CONNECTION", "connections", "key")).thenAnswer(call -> receipt.get());
        doAnswer(call -> { receipt.set(call.getArgument(0)); return null; }).when(repository).insertCommand(any(ModelCommandEntity.class));
        var first = service.create("session", "key", input);
        assertEquals(first, service.create("session", "key", input));
        verify(repository, times(1)).insert(any());
        var changed = new ModelConnectionRequest("other", input.baseUrl(), input.protocol(), input.preset(), input.apiKey(), null);
        assertEquals("MODEL_IDEMPOTENCY_CONFLICT", assertThrows(BusinessException.class, () -> service.create("session", "key", changed)).code());
    }

    @Test
    void nameEditsPreserveCheckIdentityButKeyReplacementIsImmediateAndVersionGuarded() {
        var service = service(true);
        var created = service.create("session", "create", input);
        String encrypted = stored.get().credentialCiphertext();
        var renamed = service.update("session", created.id(), "rename", new ModelConnectionRequest("new name", input.baseUrl(), input.protocol(), input.preset(), null, "1"));
        assertEquals("2", renamed.version()); assertEquals("1", renamed.configurationVersion());
        var replaced = service.update("session", created.id(), "replace", new ModelConnectionRequest("new name", input.baseUrl(), input.protocol(), input.preset(), "unverified-new-key", "2"));
        assertEquals("3", replaced.version()); assertEquals("2", replaced.configurationVersion());
        assertNotEquals(encrypted, stored.get().credentialCiphertext());
        assertEquals("MODEL_VERSION_CONFLICT", assertThrows(BusinessException.class, () -> service.update("session", created.id(), "stale", new ModelConnectionRequest(input.name(), input.baseUrl(), input.protocol(), input.preset(), null, "1"))).code());
        assertEquals("MODEL_CONNECTION_NOT_FOUND", assertThrows(BusinessException.class, () -> service.update("other", created.id(), "other", input)).code());
    }

    @Test
    void deletionErasesCredentialAndDelayedOldWritesCannotReviveTheConnection() {
        var service = service(true);
        var created = service.create("session", "create", input);
        assertTrue(service.delete("session", created.id(), "delete", "1").deleted());
        assertEquals(null, stored.get().credentialCiphertext());
        assertTrue(service.delete("session", created.id(), "another-delete", "1").deleted());
        assertEquals("MODEL_CONNECTION_NOT_FOUND", assertThrows(BusinessException.class,
            () -> service.update("session", created.id(), "late-update", input)).code());
        verify(repository, times(1)).update(any(), anyLong());
    }

    @Test
    void expiredDetailedReceiptCannotTurnAnOldCreateIntoASecondConnection() {
        var service = service(true);
        var receipt = new AtomicReference<ModelCommandEntity>();
        when(repository.command("owner", "CREATE_CONNECTION", "connections", "key")).thenAnswer(call -> receipt.get());
        doAnswer(call -> { receipt.set(call.getArgument(0)); return null; }).when(repository).insertCommand(any(ModelCommandEntity.class));
        service.create("session", "key", input);
        var completed = receipt.get();
        receipt.set(new ModelCommandEntity(completed.userId(), completed.kind(), completed.scope(), completed.requestKey(), completed.fingerprint(), null, completed.completedAt()));
        assertEquals("MODEL_RECEIPT_EXPIRED", assertThrows(BusinessException.class, () -> service.create("session", "key", input)).code());
        verify(repository, times(1)).insert(any());
    }

    @Test
    void unavailableMasterKeyCannotPersistAnUnencryptedOrFallbackCredential() {
        var service = service(false);
        assertEquals("MODEL_ENCRYPTION_UNAVAILABLE", assertThrows(BusinessException.class, () -> service.create("session", "key", input)).code());
        verify(repository, times(0)).insert(any());
    }
}
