package ai.codecraft.modules.platformagent.service;

import ai.codecraft.modules.platformagent.model.dto.PlatformActionRequest;
import ai.codecraft.modules.platformagent.model.dto.PlatformCatalogResponse;
import ai.codecraft.modules.platformagent.model.dto.PlatformCheckResponse;
import ai.codecraft.modules.platformagent.model.dto.PlatformDraftRequest;
import ai.codecraft.modules.platformagent.model.dto.PlatformVersionResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlatformAgentService {
    private final PlatformDefinitions definitions;
    private final PlatformAgentStore store;
    public PlatformAgentService(PlatformDefinitions definitions, PlatformAgentStore store) { this.definitions = definitions; this.store = store; }
    public PlatformCatalogResponse list(String session, String after) { return store.list(session, after); }
    public PlatformVersionResponse get(String session, String id) { return store.get(session, id); }
    public PlatformVersionResponse create(String session, String key, PlatformDraftRequest input) {
        String id = UUID.randomUUID().toString();
        var definition = definitions.prepare(id, input);
        return store.create(session, key, id, input, definition);
    }
    public PlatformVersionResponse change(String session, String id, String kind, String key, PlatformActionRequest input) {
        return store.change(session, id, kind, key, input);
    }
    public PlatformCheckResponse check(String session, String id, String key, PlatformActionRequest input) { return store.check(session, id, key, input); }
    public PlatformCheckResponse latest(String session, String id) { return store.latest(session, id); }
}
