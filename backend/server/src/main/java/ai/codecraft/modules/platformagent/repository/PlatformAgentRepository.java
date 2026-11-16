package ai.codecraft.modules.platformagent.repository;

import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.model.entity.PlatformRoleEntity;
import ai.codecraft.modules.platformagent.model.entity.PlatformCommandEntity;
import ai.codecraft.modules.modelaccess.model.dto.EncryptedCredentialDTO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface PlatformAgentRepository {
    @Select("SELECT * FROM platform_agent_role ORDER BY role")
    List<PlatformRoleEntity> roles();

    @Select("SELECT * FROM platform_agent_role ORDER BY role FOR UPDATE")
    List<PlatformRoleEntity> lockRoles();

    @Select("SELECT * FROM platform_agent_version WHERE (#{after}::uuid IS NULL OR id < #{after}::uuid) ORDER BY id DESC LIMIT 50")
    List<PlatformVersionEntity> list(String after);

    @Select("SELECT * FROM platform_agent_version WHERE id = #{id}::uuid")
    PlatformVersionEntity find(String id);

    @Select("SELECT * FROM platform_agent_version WHERE id = #{id}::uuid FOR UPDATE")
    PlatformVersionEntity lock(String id);

    @Insert("INSERT INTO platform_agent_version(id,role,definition,credential_key_id,credential_nonce,credential_ciphertext,created_at) "
        + "VALUES (#{id}::uuid,#{role},#{definition}::jsonb,#{credential.keyId},#{credential.nonce},#{credential.ciphertext},#{now})")
    void insert(String id, PlatformRole role, String definition, EncryptedCredentialDTO credential, Instant now);

    @Update("UPDATE platform_agent_version SET state='PUBLISHED',revision=revision+1,published_at=#{now} WHERE id=#{id}::uuid AND state='DRAFT'")
    void publish(String id, Instant now);

    @Update("UPDATE platform_agent_role SET current_version=#{id}::uuid,revision=revision+1 WHERE role=#{role}")
    void point(PlatformRole role, String id);

    @Update("UPDATE platform_agent_version SET state='RETIRED',revision=revision+1 WHERE id=#{id}::uuid")
    void retire(String id);

    @Update("UPDATE platform_agent_version SET credential_key_id=#{credential.keyId},credential_nonce=#{credential.nonce},"
        + "credential_ciphertext=#{credential.ciphertext},credential_revision=credential_revision+1,revision=revision+1 WHERE id=#{id}::uuid")
    void credential(String id, EncryptedCredentialDTO credential);

    @Select("SELECT fingerprint,resource_id FROM platform_agent_command WHERE actor_id=#{actor}::uuid AND kind=#{kind} AND command_key=#{key}")
    PlatformCommandEntity command(String actor, String kind, String key);

    @Insert("INSERT INTO platform_agent_command(actor_id,kind,command_key,fingerprint,resource_id) VALUES(#{actor}::uuid,#{kind},#{key},#{fingerprint},#{id}::uuid)")
    void commandReceipt(String actor, String kind, String key, String fingerprint, String id);

    @Insert("INSERT INTO platform_exercise_binding(exercise_id,role,version_id) VALUES(#{exercise}::uuid,#{role},#{version}::uuid)")
    void bind(String exercise, PlatformRole role, String version);

    @Select("SELECT v.* FROM platform_agent_version v JOIN platform_exercise_binding b ON b.version_id=v.id WHERE b.exercise_id=#{exercise}::uuid AND b.role=#{role}")
    PlatformVersionEntity bound(String exercise, PlatformRole role);

    @Select("SELECT count(*) FROM platform_exercise_binding WHERE version_id=#{id}::uuid")
    int bindings(String id);

    @Delete("DELETE FROM platform_exercise_binding WHERE exercise_id=#{exercise}::uuid")
    void eraseBindings(String exercise);

    @Select("SELECT count(*) * 128 FROM platform_exercise_binding WHERE exercise_id=#{exercise}::uuid")
    long bindingBytes(String exercise);

    @Select("SELECT id FROM platform_agent_version WHERE credential_key_id IS NOT NULL AND credential_key_id <> #{activeKey} AND (#{after}::uuid IS NULL OR id > #{after}::uuid) ORDER BY id LIMIT 100")
    List<String> rotationCandidates(String activeKey, String after);

    @Update("UPDATE platform_agent_version SET credential_key_id=#{credential.keyId},credential_nonce=#{credential.nonce},credential_ciphertext=#{credential.ciphertext} "
        + "WHERE id=#{id}::uuid AND credential_nonce=#{previousNonce}")
    void rewrap(String id, String previousNonce, EncryptedCredentialDTO credential);

    @Update("WITH candidates AS (SELECT id FROM platform_agent_version v WHERE state='RETIRED' AND credential_ciphertext IS NOT NULL "
        + "AND NOT EXISTS(SELECT 1 FROM platform_exercise_binding b WHERE b.version_id=v.id) "
        + "AND NOT EXISTS(SELECT 1 FROM platform_agent_check c WHERE c.version_id=v.id AND c.state IN ('QUEUED','RUNNING')) LIMIT 100) "
        + "UPDATE platform_agent_version SET credential_key_id=NULL,credential_nonce=NULL,credential_ciphertext=NULL WHERE id IN (SELECT id FROM candidates)")
    void eraseRetiredCredentials();
}
