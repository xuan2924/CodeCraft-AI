package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.FeedbackEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FeedbackRepository {
    String COLUMNS = "f.id::text,f.exercise_id::text,f.user_id::text,f.request_key,f.request_fingerprint,f.snapshot,f.state,f.reply,f.reply_key,f.created_at,f.handled_at";
    String LIVE = "NOT EXISTS(SELECT 1 FROM workspace_deletion d WHERE d.exercise_id=e.id) AND (e.retain_until IS NULL OR e.retain_until>#{now})";
    @Select("SELECT EXISTS(SELECT 1 FROM workspace_exercise e WHERE e.id=#{exercise}::uuid AND " + LIVE + ")")
    boolean live(String exercise, Instant now);
    @Select("SELECT " + COLUMNS + " FROM workspace_feedback f WHERE f.id=#{id}::uuid")
    FeedbackEntity find(String id);
    @Select("SELECT " + COLUMNS + " FROM workspace_feedback f WHERE f.exercise_id=#{exercise}::uuid AND f.request_key=#{key}")
    FeedbackEntity receipt(String exercise, String key);
    @Select("SELECT " + COLUMNS + " FROM workspace_feedback f JOIN workspace_exercise e ON e.id=f.exercise_id WHERE (#{user}::uuid IS NULL OR f.user_id=#{user}::uuid) AND (#{after}::uuid IS NULL OR f.id>#{after}::uuid) AND " + LIVE + " ORDER BY f.id LIMIT 20")
    List<FeedbackEntity> list(String user, String after, Instant now);
    @Select("SELECT count(*) FROM workspace_feedback WHERE exercise_id=#{exercise}::uuid")
    int count(String exercise);
    @Insert("INSERT INTO workspace_feedback(id,exercise_id,user_id,request_key,request_fingerprint,snapshot,state,created_at) VALUES(#{id}::uuid,#{exerciseId}::uuid,#{userId}::uuid,#{requestKey},#{requestFingerprint},#{snapshot},'PENDING',#{createdAt})")
    void insert(FeedbackEntity feedback);
    @Update("UPDATE workspace_feedback SET state='HANDLED',reply=#{reply},reply_key=#{key},handled_at=#{now} WHERE id=#{id}::uuid AND state='PENDING'")
    int reply(String id, String key, String reply, Instant now);
    // Budget the eventual bounded administrator reply up front, so it cannot bypass user capacity later.
    @Select("SELECT COALESCE(sum(octet_length(snapshot)+8192),0) FROM workspace_feedback WHERE exercise_id=#{exercise}::uuid")
    long bytes(String exercise);
    @Delete("DELETE FROM workspace_feedback WHERE exercise_id=#{exercise}::uuid")
    void erase(String exercise);
}
