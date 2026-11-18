package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record AdminProblemResponse(String id, String problemId, String version, String language, boolean executionSupported,
    String packageSha256, boolean available) {
    public record Validation(boolean valid, String problemId, String version, String language, boolean executionSupported,
        String packageSha256, boolean hasChallenge, List<String> errors) { }
    public record Published(String id, String problemId, String version, String packageSha256) { }
    public record Availability(Boolean available) { }
}
