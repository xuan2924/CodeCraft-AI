package ai.codecraft.modules.modelaccess.model.dto;

/** Discovery metadata only; encrypted or plaintext credentials are never carried by the coordinator. */
public record ModelCredentialRotationCandidateDTO(String id, String userId) {}
