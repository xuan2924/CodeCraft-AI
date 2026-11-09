package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.workspace.model.dto.ProblemResponse;
import ai.codecraft.modules.workspace.service.ProblemService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProblemController {
    private final ProblemService problems;

    public ProblemController(ProblemService problems) {
        this.problems = problems;
    }

    @GetMapping("/api/problems")
    public ApiResponse<List<ProblemResponse>> list() {
        return ApiResponse.success(problems.list());
    }

    @GetMapping("/api/problems/{id}")
    public ApiResponse<ProblemResponse> describe(@PathVariable UUID id) {
        return ApiResponse.success(problems.describe(id.toString()));
    }
}
