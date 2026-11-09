package ai.codecraft.modules.identity.service;

import java.util.List;

/** Identity isolates workspace fencing; no RPC is allowed while capturing the preparation IDs. */
public interface IdentityRevocationBarrier {
    /** Called with the user's authorization lock held; database queries only. */
    List<String> pendingPreparations(String userId, String sessionHash);

    /** Fence only the captured IDs, never a fresh user-wide selection after admission has reopened. */
    void fence(List<String> preparationIds);
}
