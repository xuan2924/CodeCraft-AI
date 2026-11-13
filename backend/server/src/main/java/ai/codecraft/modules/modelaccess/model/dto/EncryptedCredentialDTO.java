package ai.codecraft.modules.modelaccess.model.dto;

public record EncryptedCredentialDTO(String keyId, String nonce, String ciphertext) {
    @Override public String toString() { return "EncryptedCredentialDTO[keyId=" + keyId + ", value=REDACTED]"; }
}
