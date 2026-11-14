package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.model.dto.EncryptedCredentialDTO;
import ai.codecraft.modules.modelaccess.model.entity.ModelConnectionEntity;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelCredentialRotationStoreTest {
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private final String newKey = Base64.getEncoder().encodeToString("n".repeat(32).getBytes(StandardCharsets.US_ASCII));
    private final ModelCredentialCipher old = new ModelCredentialCipher(new ModelSecurityProperties("old", "old:" + key));
    private final ModelCredentialCipher current = new ModelCredentialCipher(new ModelSecurityProperties("new", "old:" + key + ",new:" + newKey));
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
    private final ModelCredentialRotationStore store = new ModelCredentialRotationStore(identity, connections, current);

    @Test
    void rewrapsOnlyTheCurrentCiphertextAfterOwnerLockWithoutChangingModelIdentity() {
        var original = old.encrypt("owner", "connection", "fixture-secret");
        when(connections.find("connection")).thenReturn(row("owner", original, null));
        when(connections.rotateCredential(anyString(), anyString(), any(), any())).thenReturn(1);
        assertThat(store.rotate("owner", "connection")).isTrue();
        var rotated = ArgumentCaptor.forClass(EncryptedCredentialDTO.class);
        var ordered = inOrder(identity, connections);
        ordered.verify(identity).lockResourceOwner("owner"); ordered.verify(connections).find("connection");
        ordered.verify(connections).rotateCredential(eq("connection"), eq("owner"), eq(original), rotated.capture());
        assertThat(rotated.getValue().keyId()).isEqualTo("new");
        var newOnly = new ModelCredentialCipher(new ModelSecurityProperties("new", "new:" + newKey));
        assertThat(newOnly.decrypt("owner", "connection", rotated.getValue())).isEqualTo("fixture-secret");
        verify(connections, never()).update(any(), anyLong());
    }

    @Test
    void deletedMissingForeignAndAlreadyCurrentRowsAreNotWritten() {
        var encrypted = current.encrypt("owner", "connection", "fixture-secret");
        when(connections.find("connection")).thenReturn(null, row("foreign", encrypted, null), row("owner", null, Instant.EPOCH), row("owner", encrypted, null));
        for (int i = 0; i < 4; i++) assertThat(store.rotate("owner", "connection")).isFalse();
        verify(connections, never()).rotateCredential(any(), any(), any(), any());
    }

    @Test
    void unreadableCiphertextIsNeverOverwrittenAndStaleCompareAndSwapDoesNotClaimSuccess() {
        when(connections.find("connection")).thenReturn(row("owner", new EncryptedCredentialDTO("missing", "bad", "bad"), null));
        assertThatThrownBy(() -> store.rotate("owner", "connection")).isInstanceOf(BusinessException.class);
        verify(connections, never()).rotateCredential(any(), any(), any(), any());
        when(connections.find("connection")).thenReturn(row("owner", old.encrypt("owner", "connection", "replacement"), null));
        assertThat(store.rotate("owner", "connection")).isFalse();
    }

    private ModelConnectionEntity row(String owner, EncryptedCredentialDTO encrypted, Instant deleted) {
        return new ModelConnectionEntity("connection", owner, "fixture", "https://fixture.test", "OPENAI_CHAT", "CUSTOM",
            encrypted == null ? null : encrypted.keyId(), encrypted == null ? null : encrypted.nonce(), encrypted == null ? null : encrypted.ciphertext(),
            8, 3, Instant.EPOCH, Instant.EPOCH, deleted);
    }
}
