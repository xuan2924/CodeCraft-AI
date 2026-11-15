package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotConversationResponse;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotConversationRepository {
    String COLUMNS = "id::text,exercise_id::text,id=exercise_id AS initial,created_at";

    @Select("SELECT " + COLUMNS + " FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid ORDER BY (id=exercise_id) DESC,created_at,id LIMIT 100")
    List<CopilotConversationResponse> list(String exercise);
    @Select("SELECT " + COLUMNS + " FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    CopilotConversationResponse receipt(String exercise, String key);
    @Select("SELECT " + COLUMNS + " FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid AND id=#{id}::uuid")
    CopilotConversationResponse find(String exercise, String id);
    @Select("SELECT count(*) FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid")
    int count(String exercise);
    @Insert("INSERT INTO copilot_conversation(id,exercise_id,request_key,created_at) VALUES(#{exercise}::uuid,#{exercise}::uuid,'initial',#{now}) ON CONFLICT(id) DO NOTHING")
    void ensureInitial(String exercise, Instant now);
    @Insert("INSERT INTO copilot_conversation(id,exercise_id,request_key,created_at) VALUES(#{id}::uuid,#{exercise}::uuid,#{key},#{now})")
    void insert(String id, String exercise, String key, Instant now);
}
