package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.TestProblemResponse;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;

final class TestTaskMapper {
    private TestTaskMapper() {}

    static TestProblemResponse problem(ProblemVersionEntity value) {
        return new TestProblemResponse(value.problemId(), value.version(), value.packageSha256(), value.executionImageDigest(), value.platform());
    }
}
