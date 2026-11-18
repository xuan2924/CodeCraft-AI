package ai.codecraft.modules.platformagent.model.dto;

public record PlatformBindingDTO(String id, String definition, String credentialKeyId, String credentialNonce, String credentialCiphertext) {
    @Override public String toString() { return "PlatformBindingDTO[REDACTED]"; }
}
