package ai.codecraft.modules.modelaccess.model.entity;

import ai.codecraft.modules.modelaccess.model.dto.EncryptedCredentialDTO;
import java.time.Instant;

public record ModelConnectionEntity(String id, String userId, String name, String baseUrl, String protocol, String preset,
    String credentialKeyId, String credentialNonce, String credentialCiphertext, long version, long configurationVersion,
    Instant createdAt, Instant updatedAt, Instant deletedAt) {
    public EncryptedCredentialDTO credential() { return new EncryptedCredentialDTO(credentialKeyId, credentialNonce, credentialCiphertext); }
    @Override public String toString() { return "ModelConnectionEntity[id=" + id + "]"; }
}
