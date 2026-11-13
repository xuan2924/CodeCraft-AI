package ai.codecraft.modules.modelaccess.security;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.model.dto.EncryptedCredentialDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/** Deployment-key encryption only: no environment/test credential fallback. */
@Component
public class ModelCredentialCipher {
    private static final int MAX_SECRET_BYTES = 4096;
    private final String activeKeyId;
    private final Map<String, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    public ModelCredentialCipher(ModelSecurityProperties properties) {
        activeKeyId = properties.activeKeyId();
        keys = properties.keyring();
    }

    public EncryptedCredentialDTO encrypt(String owner, String connection, String value) {
        if (!keys.containsKey(activeKeyId)) throw new BusinessException(ModelAccessErrorCode.MODEL_ENCRYPTION_UNAVAILABLE);
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
        }
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(owner, connection, activeKeyId));
            return new EncryptedCredentialDTO(activeKeyId, Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException failure) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_ENCRYPTION_UNAVAILABLE, failure);
        }
    }

    public String decrypt(String owner, String connection, EncryptedCredentialDTO value) {
        if (value == null || !keys.containsKey(value.keyId()) || value.nonce() == null || value.nonce().length() != 16
            || value.ciphertext() == null || value.ciphertext().length() > 6000) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_CREDENTIAL_UNAVAILABLE);
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(value.nonce());
            byte[] encrypted = Base64.getDecoder().decode(value.ciphertext());
            if (nonce.length != 12 || encrypted.length < 16 || encrypted.length > MAX_SECRET_BYTES + 16) {
                throw new IllegalArgumentException("Invalid encrypted model credential");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(value.keyId()), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(owner, connection, value.keyId()));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new BusinessException(ModelAccessErrorCode.MODEL_CREDENTIAL_UNAVAILABLE, failure);
        }
    }

    public EncryptedCredentialDTO rotate(String owner, String connection, EncryptedCredentialDTO value) {
        String plaintext = decrypt(owner, connection, value);
        return value.keyId().equals(activeKeyId) ? value : encrypt(owner, connection, plaintext);
    }

    private byte[] aad(String owner, String connection, String keyId) {
        byte[] first = owner.getBytes(StandardCharsets.UTF_8);
        byte[] second = connection.getBytes(StandardCharsets.UTF_8);
        byte[] third = keyId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(16 + first.length + second.length + third.length).putInt(1)
            .putInt(first.length).put(first).putInt(second.length).put(second).putInt(third.length).put(third).array();
    }
}
