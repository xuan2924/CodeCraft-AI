package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.AdminProblemResponse;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdminProblemStoreTest {
    private final ProblemRepository repository = mock(ProblemRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final ProblemTransactionService problems = new ProblemTransactionService(repository);
    private final AdminProblemStore store = new AdminProblemStore(identity, repository, problems);

    @Test void publishReplaysImmutableIdentityAndRejectsBothKindsOfConflict() {
        var version = new ProblemVersionEntity("candidate", "p", "1.0.0", "sha", "image", "linux/amd64", "{}");
        var original = new AdminProblemResponse.Published("persisted", "p", "1.0.0", "sha");
        when(identity.authorizeMutation("s")).thenReturn(new SessionAuthorizationDTO("actor", "ADMIN", false, 1, Instant.MAX));
        when(repository.claim("actor", "key", "digest")).thenReturn(1, 0);
        when(repository.fingerprint("actor", "key")).thenReturn("digest");
        when(repository.matches(version)).thenReturn(true);
        when(repository.published("p", "1.0.0")).thenReturn(original);
        assertThat(store.publish("s", "key", "digest", version)).isEqualTo(original);
        assertThat(store.publish("s", "key", "digest", version)).isEqualTo(original);
        verify(repository, times(1)).insert(version);
        assertThatThrownBy(() -> store.publish("s", "key", "different", version)).hasMessage("IDEMPOTENCY_CONFLICT");
        when(repository.claim("actor", "fresh", "digest")).thenReturn(1);
        when(repository.matches(version)).thenReturn(false);
        assertThatThrownBy(() -> store.publish("s", "fresh", "digest", version)).hasMessage("PUBLICATION_CONFLICT");
    }

    @Test void unsupportedAndDelistedVersionsCannotStart() {
        when(repository.availableForStart("v")).thenReturn(true, false);
        when(repository.language("v")).thenReturn("PYTHON_PYTEST");
        assertThatThrownBy(() -> problems.requireAvailable("v")).hasMessage("EXECUTION_UNSUPPORTED");
        assertThatThrownBy(() -> problems.requireAvailable("v")).hasMessage("RESOURCE_NOT_FOUND");
    }
}
