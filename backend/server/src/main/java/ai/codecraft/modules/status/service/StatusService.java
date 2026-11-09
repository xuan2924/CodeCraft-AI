package ai.codecraft.modules.status.service;

import ai.codecraft.modules.status.mapper.DatabaseStatusMapper;
import ai.codecraft.modules.status.model.DependencyStatusResponse;
import ai.codecraft.modules.status.model.DependencyStatusResponse.Availability;
import ai.codecraft.status.v1.CheckRequest;
import ai.codecraft.status.v1.SandboxStatusGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class StatusService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatusService.class);
    private final DatabaseStatusMapper database;
    private final SandboxStatusGrpc.SandboxStatusBlockingStub sandbox;

    public StatusService(DatabaseStatusMapper database, ManagedChannel channel) {
        this.database = database;
        this.sandbox = SandboxStatusGrpc.newBlockingStub(channel);
    }

    public DependencyStatusResponse check() {
        Availability databaseStatus = Availability.UNAVAILABLE;
        Availability sandboxStatus = Availability.UNAVAILABLE;
        // Diagnostics stay server-side; HTTP responses contain only availability enums.
        try {
            if (database.isMigrated()) {
                databaseStatus = Availability.AVAILABLE;
            }
        } catch (DataAccessException failure) {
            LOGGER.warn("Dependency status check failed (database)", failure);
        }
        try {
            if (sandbox.withDeadlineAfter(1, TimeUnit.SECONDS)
                    .check(CheckRequest.getDefaultInstance()).getServing()) {
                sandboxStatus = Availability.AVAILABLE;
            }
        } catch (StatusRuntimeException failure) {
            LOGGER.warn("Dependency status check failed (sandbox), code={}", failure.getStatus().getCode(), failure);
        }
        return new DependencyStatusResponse(databaseStatus, sandboxStatus);
    }
}
