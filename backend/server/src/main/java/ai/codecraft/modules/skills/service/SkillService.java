package ai.codecraft.modules.skills.service;

import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import ai.codecraft.modules.skills.model.dto.SkillPreparedDTO;
import ai.codecraft.modules.skills.model.dto.SkillResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Parsing and large payload encoding stay outside the owner-lock transaction. */
@Service
public class SkillService {
    private final SkillPackageParser parser;
    private final SkillStore store;
    private final ObjectMapper json;
    public SkillService(SkillPackageParser parser, SkillStore store, @Qualifier("skillJson") ObjectMapper json) {
        this.parser = parser; this.store = store; this.json = json;
    }
    public SkillResponse save(String session, String id, String key, String expectedVersion, SkillPackageRequest request) {
        var contents = parser.parse(request);
        try {
            String payload = json.writeValueAsString(new SkillPackageRequest(1, contents.files()));
            var prepared = new SkillPreparedDTO(contents, payload, SkillDigest.hash(payload));
            return store.save(session, id, key, expectedVersion, prepared);
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode validated Skill", failure); }
    }
    public List<SkillResponse> list(String session) { return store.list(session); }
    public SkillPackageRequest contents(String session, String id) {
        String payload = store.contents(session, id);
        try { return json.readValue(payload, SkillPackageRequest.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid stored Skill package", failure); }
    }
    public SkillResponse change(String session, String id, String key, String expectedVersion, boolean enabled, boolean deleted) {
        return store.change(session, id, key, expectedVersion, enabled, deleted);
    }
}
