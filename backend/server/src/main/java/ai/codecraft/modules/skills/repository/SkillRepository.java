package ai.codecraft.modules.skills.repository;

import ai.codecraft.modules.skills.model.dto.SkillResponse;
import ai.codecraft.modules.skills.model.entity.SkillCommandEntity;
import ai.codecraft.modules.skills.model.entity.SkillEntity;
import ai.codecraft.modules.skills.model.entity.SkillVersionEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkillRepository {
    @Select("SELECT id::text,user_id::text,name,current_version_id::text,version,enabled,deleted FROM personal_skill WHERE id=#{id}::uuid AND user_id=#{owner}::uuid")
    SkillEntity find(String owner, String id);
    @Select("SELECT id::text,skill_id::text,user_id::text,name,description,files_payload,content_bytes,content_hash FROM personal_skill_version WHERE id=#{id}::uuid AND user_id=#{owner}::uuid")
    SkillVersionEntity version(String owner, String id);
    @Select("SELECT s.id::text,s.name,v.description,s.version::text,s.current_version_id::text AS content_version,s.enabled,s.deleted,v.content_bytes FROM personal_skill s JOIN personal_skill_version v ON v.id=s.current_version_id WHERE s.user_id=#{owner}::uuid AND NOT s.deleted ORDER BY s.name,s.id LIMIT 1000")
    List<SkillResponse> list(String owner);
    @Select("SELECT count(*) FROM personal_skill WHERE user_id=#{owner}::uuid AND NOT deleted")
    long count(String owner);
    @Select("SELECT coalesce(sum(v.content_bytes),0) FROM personal_skill s JOIN personal_skill_version v ON v.id=s.current_version_id WHERE s.user_id=#{owner}::uuid AND NOT s.deleted")
    long bytes(String owner);
    @Select("SELECT EXISTS(SELECT 1 FROM personal_skill WHERE user_id=#{owner}::uuid AND name=#{name} AND NOT deleted AND (#{except}::uuid IS NULL OR id<>#{except}::uuid))")
    boolean nameExists(String owner, String name, String except);
    @Insert("INSERT INTO personal_skill(id,user_id,name,current_version_id,version,enabled,deleted,updated_at) VALUES(#{value.id}::uuid,#{value.userId}::uuid,#{value.name},#{value.currentVersionId}::uuid,#{value.version},#{value.enabled},#{value.deleted},#{now})")
    void insert(SkillEntity value, Instant now);
    @Insert("INSERT INTO personal_skill_version(id,skill_id,user_id,name,description,files_payload,content_bytes,content_hash,created_at) VALUES(#{value.id}::uuid,#{value.skillId}::uuid,#{value.userId}::uuid,#{value.name},#{value.description},#{value.filesPayload},#{value.contentBytes},#{value.contentHash},#{now})")
    void insertVersion(SkillVersionEntity value, Instant now);
    @Update("UPDATE personal_skill SET name=#{value.name},current_version_id=#{value.currentVersionId}::uuid,version=#{value.version},enabled=#{value.enabled},deleted=#{value.deleted},updated_at=#{now} WHERE id=#{value.id}::uuid AND user_id=#{value.userId}::uuid AND version=#{expected}")
    int update(SkillEntity value, long expected, Instant now);
    @Select("SELECT fingerprint,response_payload FROM personal_skill_command WHERE user_id=#{owner}::uuid AND request_key=#{key}")
    SkillCommandEntity command(String owner, String key);
    @Insert("INSERT INTO personal_skill_command(user_id,request_key,fingerprint,response_payload,created_at) VALUES(#{owner}::uuid,#{key},#{fingerprint},#{payload},#{now})")
    void commandResult(String owner, String key, String fingerprint, String payload, Instant now);
    @Delete("DELETE FROM personal_skill_version v WHERE v.user_id=#{owner}::uuid AND NOT EXISTS(SELECT 1 FROM personal_skill s WHERE s.current_version_id=v.id) AND NOT EXISTS(SELECT 1 FROM copilot_skill_snapshot r WHERE r.version_id=v.id)")
    void reclaim(String owner);
}
