package ai.codecraft.status;

import ai.codecraft.status.DependencyStatus.Availability;
import ai.codecraft.status.v1.CheckRequest;
import ai.codecraft.status.v1.SandboxStatusGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class StatusService {
    private final DatabaseStatusMapper database;
    private final SandboxStatusGrpc.SandboxStatusBlockingStub sandbox;

    public StatusService(DatabaseStatusMapper database, ManagedChannel channel) {
        this.database = database;
        this.sandbox = SandboxStatusGrpc.newBlockingStub(channel);
    }

    public DependencyStatus check() {
        Availability databaseStatus = Availability.UNAVAILABLE;
        Availability sandboxStatus = Availability.UNAVAILABLE;
        // Expected dependency failures are intentionally represented without exception text.
        try {
            if (database.isMigrated()) {
                databaseStatus = Availability.AVAILABLE;
            }
        } catch (DataAccessException ignored) {
            // A failed query never reuses an earlier success.
        }
        try {
            if (sandbox.withDeadlineAfter(1, TimeUnit.SECONDS)
                    .check(CheckRequest.getDefaultInstance()).getServing()) {
                sandboxStatus = Availability.AVAILABLE;
            }
        } catch (StatusRuntimeException ignored) {
            // This is process liveness, not Docker/execution readiness.
        }
        return new DependencyStatus(databaseStatus, sandboxStatus);
    }
}
