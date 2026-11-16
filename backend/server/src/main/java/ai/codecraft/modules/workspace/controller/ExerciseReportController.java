package ai.codecraft.modules.workspace.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.security.IdentityHttp;
import ai.codecraft.modules.workspace.model.dto.ExerciseReportResponse;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import ai.codecraft.modules.workspace.model.dto.ReportRetryRequest;
import ai.codecraft.modules.workspace.model.dto.ReportReviewRequest;
import ai.codecraft.modules.workspace.service.ExerciseReportService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExerciseReportController {
    private final ExerciseReportService reports;
    public ExerciseReportController(ExerciseReportService reports) { this.reports = reports; }
    @GetMapping("/api/exercises/{exercise}/report")
    public ApiResponse<ExerciseReportResponse> view(@PathVariable UUID exercise, HttpServletRequest request) {
        return ApiResponse.success(reports.view(IdentityHttp.sessionHash(request), exercise.toString()));
    }
    @PostMapping("/api/exercises/{exercise}/report/review")
    public ResponseEntity<ApiResponse<ExerciseReportResponse>> review(@PathVariable UUID exercise, @RequestBody ReportReviewRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(reports.review(IdentityHttp.sessionHash(request), exercise.toString(), key, input)));
    }
    @PostMapping("/api/exercises/{exercise}/report/retry")
    public ResponseEntity<ApiResponse<ExerciseReportResponse>> retry(@PathVariable UUID exercise, @RequestBody ReportRetryRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.success(reports.retry(IdentityHttp.sessionHash(request), exercise.toString(), key, input.expectedGeneration())));
    }
    @GetMapping("/api/exercises/{exercise}/report/evidence")
    public ApiResponse<ReportEvidenceDTO> evidence(@PathVariable UUID exercise, @RequestParam long generation, @RequestParam String id, HttpServletRequest request) {
        return ApiResponse.success(reports.evidence(IdentityHttp.sessionHash(request), exercise.toString(), generation, id));
    }
    @GetMapping("/api/exercises/{exercise}/report/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID exercise, @RequestParam long generation, HttpServletRequest request) throws IOException {
        byte[] bytes = reports.pdf(IdentityHttp.sessionHash(request), exercise.toString(), generation);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(bytes.length)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"codecraft-" + exercise + "-" + generation + ".pdf\"")
            .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
