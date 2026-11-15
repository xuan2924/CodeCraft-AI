package ai.codecraft.modules.workspace.model.dto;

/** An immutable checkpoint for projection outside the transaction and exact revalidation before consumption. */
public record CommandEvidenceDTO(long version, String payload) {}
