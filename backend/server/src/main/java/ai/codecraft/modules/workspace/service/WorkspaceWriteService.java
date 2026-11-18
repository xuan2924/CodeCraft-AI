package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.workspace.model.dto.ControlResponse;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceWriteService {
    private final WorkspaceWriteTransactionService store;
    private final WorkspaceWriteCoordinator coordinator;
    private final WorkspaceBatchCodec codec;
    private final IdentitySecrets secrets;
    private final StorageCapacityService capacity;

    public WorkspaceWriteService(WorkspaceWriteTransactionService store, WorkspaceWriteCoordinator coordinator,
                                 WorkspaceBatchCodec codec, IdentitySecrets secrets, StorageCapacityService capacity) {
        this.store = store;
        this.coordinator = coordinator;
        this.codec = codec;
        this.secrets = secrets;
        this.capacity = capacity;
    }

    public ControlResponse control(String session, String exercise, String kind, String key, String credential) {
        String candidate = kind.equals("RELEASE") ? null : secrets.token();
        WorkspaceOperationEntity operation = store.existingControl(session, exercise, kind, key);
        if (operation == null) operation = kind.equals("RELEASE")
            ? store.control(session, exercise, kind, key, credential, IdentitySecrets.digest(candidate), null)
            : capacity.admit(session, exercise, measured -> store.control(session, exercise, kind, key, credential, IdentitySecrets.digest(candidate), measured));
        coordinator.tryDrive(operation.id());
        return store.view(session, exercise, operation.id(), candidate);
    }

    public ControlResponse control(String session, String exercise) { return store.view(session, exercise, null, null); }

    public WorkspaceOperationResponse batch(String session, String exercise, String key, String credential, FileBatchRequest batch) {
        String payload = codec.encode(batch, store.language(session, exercise));
        WorkspaceOperationEntity operation = store.existingBatch(session, exercise, key, payload);
        if (operation == null) {
            String restore = store.ensureOpen(session, exercise, credential);
            if (restore != null) coordinator.tryDrive(restore);
            operation = capacity.admit(session, exercise, measured -> store.batch(session, exercise, key, credential, payload, Long.parseLong(batch.expectedRevision()), measured));
        }
        if (operation.state().equals("PENDING") || operation.state().equals("PENDING_RECONCILIATION")) coordinator.tryMutation(operation);
        WorkspaceOperationResponse result = store.result(session, operation.id());
        if (result.state().equals("REJECTED")) throw new BusinessException(WorkspaceErrorCode.valueOf(result.failureCode()));
        return result;
    }
}
