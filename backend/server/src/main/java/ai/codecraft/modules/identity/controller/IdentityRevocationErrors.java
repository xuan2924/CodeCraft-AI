package ai.codecraft.modules.identity.controller;

import ai.codecraft.common.model.ApiResponse;
import ai.codecraft.modules.identity.model.dto.RevocationOperationResponse;
import ai.codecraft.modules.identity.model.exception.IdentityRevocationPending;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IdentityRevocationErrors {
    @ExceptionHandler(IdentityRevocationPending.class)
    public ResponseEntity<ApiResponse<RevocationOperationResponse>> pending(IdentityRevocationPending failure) {
        String id = failure.operationId();
        return ResponseEntity.accepted().body(ApiResponse.success(new RevocationOperationResponse(id, "PENDING", "/api/operations/" + id)));
    }
}
