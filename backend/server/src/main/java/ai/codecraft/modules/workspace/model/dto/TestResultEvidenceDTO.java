package ai.codecraft.modules.workspace.model.dto;

/** Immutable stored evidence; decode only after leaving the owner/record transaction. */
public record TestResultEvidenceDTO(long version, String state, String payload) {
    @Override public String toString() { return "TestResultEvidenceDTO[version=" + version + ", content=redacted]"; }
}
