package ai.codecraft.modules.workspace.model.entity;

public record ProblemVersionEntity(String id, String problemId, String version, String packageSha256,
                                   String executionImageDigest, String platform, String artifact) {}
