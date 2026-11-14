package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelCredentialRotationProperties;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.model.dto.ModelCredentialRotationCandidateDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ModelCredentialRotationCoordinatorTest {
    private final ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
    private final ModelCredentialRotationStore store = mock(ModelCredentialRotationStore.class);
    private final ModelSecurityProperties security = new ModelSecurityProperties("new", "new:" + Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void disabledJobDoesNothingAndEnabledJobRequiresConfiguredEncryption() {
        new ModelCredentialRotationCoordinator(new ModelCredentialRotationProperties(false), new ModelSecurityProperties("", ""), connections, store).rotateBatch();
        verifyNoInteractions(connections, store);
        assertThatThrownBy(() -> new ModelCredentialRotationCoordinator(new ModelCredentialRotationProperties(true), new ModelSecurityProperties("", ""), connections, store))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unreadableRowsDoNotStarveLaterOwnersAndEmptyTailRestartsTheSweep() {
        var first = new ModelCredentialRotationCandidateDTO("first", "owner-a");
        var second = new ModelCredentialRotationCandidateDTO("second", "owner-b");
        when(connections.rotationCandidates("new", null)).thenReturn(List.of(first, second), List.of());
        when(connections.rotationCandidates("new", "second")).thenReturn(List.of());
        when(store.rotate("owner-a", "first")).thenThrow(new BusinessException(ModelAccessErrorCode.MODEL_CREDENTIAL_UNAVAILABLE));
        var coordinator = new ModelCredentialRotationCoordinator(new ModelCredentialRotationProperties(true), security, connections, store);
        coordinator.rotateBatch(); coordinator.rotateBatch(); coordinator.rotateBatch();
        verify(store).rotate("owner-b", "second");
        verify(connections, times(2)).rotationCandidates("new", null);
        verify(connections).rotationCandidates("new", "second");
    }

    @Test
    void databaseFailureStopsTheBatchAndRetriesItsUncommittedPosition() {
        when(connections.rotationCandidates("new", null)).thenReturn(List.of(new ModelCredentialRotationCandidateDTO("first", "owner-a"), new ModelCredentialRotationCandidateDTO("second", "owner-b")));
        when(store.rotate("owner-a", "first")).thenThrow(new DataAccessResourceFailureException("fixture-private-message"));
        var coordinator = new ModelCredentialRotationCoordinator(new ModelCredentialRotationProperties(true), security, connections, store);
        coordinator.rotateBatch(); coordinator.rotateBatch();
        verify(connections, times(2)).rotationCandidates("new", null);
        verify(store, never()).rotate("owner-b", "second");
    }
}
