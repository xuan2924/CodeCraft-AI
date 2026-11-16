package ai.codecraft.modules.platformagent.repository;

import ai.codecraft.modules.platformagent.model.entity.PlatformCheckEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlatformCheckRepository {
    String COLUMNS = "id,version_id,credential_revision,actor_id,session_hash,state,created_at,deadline_at,finished_at,error_code,price_snapshot,usage_receipt";
    @Select("SELECT " + COLUMNS + " FROM platform_agent_check WHERE id=#{id}::uuid")
    PlatformCheckEntity find(String id);

    @Select("SELECT " + COLUMNS + " FROM platform_agent_check WHERE version_id=#{version}::uuid ORDER BY sequence DESC LIMIT 1")
    PlatformCheckEntity latest(String version);

    @Select("SELECT count(*) FROM platform_agent_check WHERE actor_id=#{actor}::uuid AND state IN ('QUEUED','RUNNING')")
    int active(String actor);

    @Select("SELECT count(*) FROM platform_agent_check WHERE actor_id=#{actor}::uuid AND created_at > #{since}")
    int recent(String actor, Instant since);

    @Insert("INSERT INTO platform_agent_check(id,version_id,credential_revision,actor_id,session_hash,state,created_at,deadline_at) "
        + "VALUES(#{id}::uuid,#{versionId}::uuid,#{credentialRevision},#{actorId}::uuid,#{sessionHash},'QUEUED',#{createdAt},#{deadlineAt})")
    void insert(PlatformCheckEntity value);

    @Select("SELECT id FROM platform_agent_check WHERE state='QUEUED' ORDER BY created_at LIMIT 100")
    List<String> queued();

    @Update("UPDATE platform_agent_check SET state='RUNNING',deadline_at=#{deadline},price_snapshot=#{price}::jsonb WHERE id=#{id}::uuid AND state='QUEUED' AND deadline_at > #{now}")
    int claim(String id, Instant now, Instant deadline, String price);

    @Update("UPDATE platform_agent_check SET state=#{state},finished_at=#{now},error_code=#{error},usage_receipt=#{usage}::jsonb "
        + "WHERE id=#{id}::uuid AND state IN ('QUEUED','RUNNING')")
    void finish(String id, String state, Instant now, String error, String usage);

    @Update("UPDATE platform_agent_check SET state='FAILED',finished_at=#{now},error_code='PLATFORM_CHECK_INTERRUPTED' "
        + "WHERE id IN (SELECT id FROM platform_agent_check WHERE state IN ('QUEUED','RUNNING') AND deadline_at <= #{now} LIMIT 100)")
    void expire(Instant now);

    @Delete("DELETE FROM platform_agent_check WHERE id IN (SELECT c.id FROM platform_agent_check c WHERE c.state IN ('SUCCEEDED','FAILED') "
        + "AND c.finished_at < #{before} AND EXISTS(SELECT 1 FROM platform_agent_check newer WHERE newer.version_id=c.version_id AND newer.sequence>c.sequence) LIMIT 100)")
    void prune(Instant before);
}
