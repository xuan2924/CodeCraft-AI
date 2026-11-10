package ai.codecraft.modules.workspace.model.dto;

import java.util.List;
import java.util.Map;

public record ProblemResponse(String problemVersionId, String problemId, String version, String title,
                              String requirements, int durationSeconds, List<String> editableRoots,
                              Map<String, String> acceptanceFiles, String packageSha256,
                              String executionImageDigest, String platform, String imageIdentityKind, boolean executionAvailable) {}
