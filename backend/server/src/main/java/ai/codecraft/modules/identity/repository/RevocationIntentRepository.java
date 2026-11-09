package ai.codecraft.modules.identity.repository;

import ai.codecraft.modules.identity.model.entity.RevocationIntentEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RevocationIntentRepository {
    String COLUMNS = "id::text, actor_id::text, target_id::text, actor_session_hash, scope_session_hash, kind, request_key, fingerprint, state, created_at, array_to_string(preparation_ids, ',') AS preparation_ids";
    @Select("SELECT " + COLUMNS + " FROM identity_revocation_intent WHERE id = #{id}::uuid")
    RevocationIntentEntity find(String id);
    @Select("SELECT " + COLUMNS + " FROM identity_revocation_intent WHERE target_id = #{target}::uuid AND state IN ('PENDING', 'FENCED')")
    RevocationIntentEntity pending(String target);
    @Select("SELECT " + COLUMNS + " FROM identity_revocation_intent WHERE actor_id = #{actor}::uuid AND target_id = #{target}::uuid AND kind = #{kind} AND request_key = #{key}")
    RevocationIntentEntity receipt(@Param("actor") String actor, @Param("target") String target, @Param("kind") String kind, @Param("key") String key);
    @Insert("""
        INSERT INTO identity_revocation_intent(id, actor_id, target_id, actor_session_hash, scope_session_hash, kind, request_key, fingerprint, state, created_at, preparation_ids)
        VALUES (#{id}::uuid, #{actorId}::uuid, #{targetId}::uuid, #{actorSessionHash}, #{scopeSessionHash}, #{kind}, #{requestKey}, #{fingerprint}, #{state}, #{createdAt}, string_to_array(#{preparationIds}, ',')::uuid[])
        """)
    void insert(RevocationIntentEntity intent);
    @Select("SELECT " + COLUMNS + " FROM identity_revocation_intent WHERE state IN ('PENDING', 'FENCED') ORDER BY last_checked_at, id LIMIT 16")
    List<RevocationIntentEntity> pendingFences();
    @Update("UPDATE identity_revocation_intent SET state = #{state}, last_checked_at = CURRENT_TIMESTAMP WHERE id = #{id}::uuid AND state IN ('PENDING', 'FENCED')")
    void state(@Param("id") String id, @Param("state") String state);
}
