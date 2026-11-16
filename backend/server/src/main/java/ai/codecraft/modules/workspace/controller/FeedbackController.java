package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.FeedbackPreviewDTO;
import ai.codecraft.modules.workspace.model.dto.FeedbackReplyRequest;
import ai.codecraft.modules.workspace.model.dto.FeedbackRequest;
import ai.codecraft.modules.workspace.model.dto.FeedbackResponse;
import ai.codecraft.modules.workspace.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedbackController {
    private final FeedbackService feedback;
    public FeedbackController(FeedbackService feedback) { this.feedback = feedback; }
    @PostMapping("/api/exercises/{exercise}/feedback/preview")
    public ApiResponse<FeedbackPreviewDTO> preview(@PathVariable UUID exercise, @RequestBody FeedbackRequest input, HttpServletRequest request) {
        return ApiResponse.success(feedback.preview(IdentityHttp.sessionHash(request), exercise.toString(), input));
    }
    @PostMapping("/api/exercises/{exercise}/feedback")
    public ApiResponse<FeedbackResponse> submit(@PathVariable UUID exercise, @RequestBody FeedbackRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(feedback.submit(IdentityHttp.sessionHash(request), exercise.toString(), key, input));
    }
    @GetMapping("/api/feedback")
    public ApiResponse<List<FeedbackResponse>> list(@RequestParam(required = false) String after, HttpServletRequest request) {
        return ApiResponse.success(feedback.list(IdentityHttp.sessionHash(request), false, after));
    }
    @GetMapping("/api/admin/feedback")
    public ApiResponse<List<FeedbackResponse>> inbox(@RequestParam(required = false) String after, HttpServletRequest request) {
        return ApiResponse.success(feedback.list(IdentityHttp.sessionHash(request), true, after));
    }
    @PostMapping("/api/admin/feedback/{id}/reply")
    public ApiResponse<FeedbackResponse> reply(@PathVariable UUID id, @RequestBody FeedbackReplyRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ApiResponse.success(feedback.reply(IdentityHttp.sessionHash(request), id.toString(), key, input.reply()));
    }
}
