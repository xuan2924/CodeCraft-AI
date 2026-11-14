package ai.codecraft.modules.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.PreparationAuthorizationDTO;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import ai.codecraft.modules.workspace.service.StorageCapacityStore;
import ai.codecraft.modules.workspace.service.ProblemTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparationAuthorizationTest {
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }

    @Test
    void authorityExpiresExactlyAtDeadlineAndCannotBeCommittedOrRevived() {
        MutableClock clock = new MutableClock();
        var repository = mock(ExerciseRepository.class);
        var identity = mock(IdentityTransactionService.class);
        var service = new ExerciseTransactionService(repository, identity, mock(ProblemTransactionService.class), clock,
            mock(IdentitySecrets.class), mock(WorkspaceCoordinationStore.class), new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), mock(StorageCapacityStore.class), mock(ExerciseModelService.class));
        Instant deadline = clock.instant().plusSeconds(30);
        var operation = new WorkspaceOperationEntity("operation", "owner", "exercise", "workspace", "PREPARE", "owner", "key", "fingerprint", "PENDING", clock.instant());
        var authority = new PreparationAuthorizationDTO("session", deadline);
        when(repository.preparationAuthorization("exercise")).thenReturn(authority);
        when(identity.tryAuthorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, deadline));
        assertThat(service.preparationAuthorization(operation)).isEqualTo(authority);
        clock.now = deadline.minusNanos(1);
        assertThat(service.preparationAuthorization(operation)).isEqualTo(authority);
        clock.now = deadline;
        assertThat(service.preparationAuthorization(operation)).isNull();
        assertThatThrownBy(() -> service.prepared(operation, 0, "[]")).isInstanceOf(IdentityFailure.class);
        clock.now = deadline.plusSeconds(7 * 24 * 3600);
        assertThat(service.preparationAuthorization(operation)).isNull();
    }

    @Test
    void missingRevokedRestrictedAndForeignAuthorityFailClosed() {
        MutableClock clock = new MutableClock();
        var repository = mock(ExerciseRepository.class);
        var identity = mock(IdentityTransactionService.class);
        var service = new ExerciseTransactionService(repository, identity, mock(ProblemTransactionService.class), clock,
            mock(IdentitySecrets.class), mock(WorkspaceCoordinationStore.class), new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), mock(StorageCapacityStore.class), mock(ExerciseModelService.class));
        var operation = new WorkspaceOperationEntity("operation", "owner", "exercise", "workspace", "PREPARE", "owner", "key", "fingerprint", "PENDING", clock.instant());
        assertThat(service.preparationAuthorization(operation)).isNull();
        Instant deadline = clock.instant().plusSeconds(30);
        when(repository.preparationAuthorization("exercise")).thenReturn(new PreparationAuthorizationDTO("session", deadline));
        when(identity.tryAuthorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("other", "USER", false, 1, deadline));
        assertThat(service.preparationAuthorization(operation)).isNull();
        when(identity.tryAuthorizeMutation("session")).thenReturn(null);
        assertThat(service.preparationAuthorization(operation)).isNull();
    }
}
