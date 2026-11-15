package ai.codecraft.modules.skills.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.model.dto.SkillPreparedDTO;
import ai.codecraft.modules.skills.model.dto.SkillResponse;
import ai.codecraft.modules.skills.model.entity.SkillEntity;
import ai.codecraft.modules.skills.model.entity.SkillVersionEntity;
import ai.codecraft.modules.skills.model.exception.SkillErrorCode;
import ai.codecraft.modules.skills.repository.SkillRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillStore {
    private final SkillRepository repository;
    private final IdentityTransactionService identity;
    private final SkillProperties limits;
    private final ObjectMapper json;
    private final Clock clock;
    public SkillStore(SkillRepository repository, IdentityTransactionService identity, SkillProperties limits,
        @Qualifier("applicationJson") ObjectMapper json, Clock clock) {
        this.repository = repository; this.identity = identity; this.limits = limits; this.json = json; this.clock = clock;
    }
    @Transactional
    public SkillResponse save(String session, String id, String key, String expectedVersion, SkillPreparedDTO prepared) {
        String owner = user(identity.authorizeMutation(session));
        String fingerprint = SkillDigest.hash("SAVE|" + id + "|" + expectedVersion + "|" + prepared.hash());
        var replay = replay(owner, key, fingerprint);
        if (replay != null) return replay;
        var current = id == null ? null : owned(owner, id);
        if (current != null) expected(current, expectedVersion);
        else if (expectedVersion != null) throw new BusinessException(SkillErrorCode.SKILL_VERSION_CONFLICT);
        if (current == null && repository.count(owner) >= limits.maxSkills()) throw new BusinessException(SkillErrorCode.SKILL_LIMIT_EXCEEDED);
        var contents = prepared.contents();
        if (repository.nameExists(owner, contents.name(), id)) throw new BusinessException(SkillErrorCode.SKILL_NAME_CONFLICT);
        long oldBytes = current == null ? 0 : repository.version(owner, current.currentVersionId()).contentBytes();
        if (repository.bytes(owner) - oldBytes + contents.contentBytes() > limits.maxOriginalBytes()) throw new BusinessException(SkillErrorCode.SKILL_LIMIT_EXCEEDED);
        String skillId = current == null ? UUID.randomUUID().toString() : current.id();
        var version = new SkillVersionEntity(UUID.randomUUID().toString(), skillId, owner, contents.name(), contents.description(), prepared.payload(), contents.contentBytes(), prepared.hash());
        var value = new SkillEntity(skillId, owner, contents.name(), version.id(), current == null ? 1 : Math.incrementExact(current.version()), current != null && current.enabled(), false);
        repository.insertVersion(version, clock.instant());
        if (current == null) repository.insert(value, clock.instant());
        else update(value, current.version());
        var response = describe(value, version);
        complete(owner, key, fingerprint, response);
        repository.reclaim(owner);
        return response;
    }
    @Transactional
    public SkillResponse change(String session, String id, String key, String expectedVersion, boolean enabled, boolean deleted) {
        String owner = user(identity.authorizeMutation(session));
        String fingerprint = SkillDigest.hash("CHANGE|" + id + "|" + expectedVersion + "|" + enabled + "|" + deleted);
        var replay = replay(owner, key, fingerprint);
        if (replay != null) return replay;
        var current = owned(owner, id); expected(current, expectedVersion);
        var version = repository.version(owner, current.currentVersionId());
        var value = new SkillEntity(id, owner, current.name(), deleted ? null : current.currentVersionId(), Math.incrementExact(current.version()), !deleted && enabled, deleted);
        update(value, current.version());
        var response = describe(value, version);
        complete(owner, key, fingerprint, response);
        repository.reclaim(owner);
        return response;
    }
    @Transactional
    public List<SkillResponse> list(String session) { return repository.list(user(identity.authorizePrivateDelivery(session))); }
    @Transactional
    public String contents(String session, String id) {
        String owner = user(identity.authorizePrivateDelivery(session));
        var value = owned(owner, id);
        return repository.version(owner, value.currentVersionId()).filesPayload();
    }
    private SkillEntity owned(String owner, String id) {
        var current = repository.find(owner, id);
        if (current == null || current.deleted()) throw new BusinessException(SkillErrorCode.SKILL_NOT_FOUND);
        return current;
    }
    private void update(SkillEntity value, long expected) {
        if (repository.update(value, expected, clock.instant()) != 1) throw new BusinessException(SkillErrorCode.SKILL_VERSION_CONFLICT);
    }
    private void expected(SkillEntity value, String expected) {
        if (!Long.toString(value.version()).equals(expected)) throw new BusinessException(SkillErrorCode.SKILL_VERSION_CONFLICT);
    }
    private String user(SessionAuthorizationDTO grant) {
        if (!"USER".equals(grant.role())) throw new BusinessException(IdentityErrorCode.FORBIDDEN);
        return grant.userId();
    }
    private SkillResponse describe(SkillEntity value, SkillVersionEntity version) {
        return new SkillResponse(value.id(), value.name(), version.description(), Long.toString(value.version()), version.id(), value.enabled(), value.deleted(), version.contentBytes());
    }
    private SkillResponse replay(String owner, String key, String fingerprint) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new BusinessException(SkillErrorCode.SKILL_INVALID_PACKAGE);
        var previous = repository.command(owner, key);
        if (previous == null) return null;
        if (!previous.fingerprint().equals(fingerprint)) throw new BusinessException(SkillErrorCode.SKILL_IDEMPOTENCY_CONFLICT);
        try { return json.readValue(previous.responsePayload(), SkillResponse.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid Skill command receipt", failure); }
    }
    private void complete(String owner, String key, String fingerprint, SkillResponse response) {
        try { repository.commandResult(owner, key, fingerprint, json.writeValueAsString(response), clock.instant()); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode Skill receipt", failure); }
    }
}
