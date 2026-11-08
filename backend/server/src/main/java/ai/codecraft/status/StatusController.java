package ai.codecraft.status;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final StatusService status;

    public StatusController(StatusService status) {
        this.status = status;
    }

    @GetMapping("/api/internal/status")
    public ResponseEntity<DependencyStatus> check() {
        DependencyStatus result = status.check();
        return ResponseEntity.status(result.allAvailable() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore()).body(result);
    }
}
