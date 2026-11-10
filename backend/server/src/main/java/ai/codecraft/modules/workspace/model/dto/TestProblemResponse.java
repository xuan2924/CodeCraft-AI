package ai.codecraft.modules.workspace.model.dto;

public record TestProblemResponse(String problemId, String version, String packageSha256,
    String executionImageDigest, String platform) {}
