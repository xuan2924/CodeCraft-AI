package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotConversationResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotConversationRepository {
    String COLUMNS = "id::text,exercise_id::text,id=exercise_id AS initial,created_at,fork_source::text,fork_message::text";

    @Select("SELECT " + COLUMNS + " FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid AND NOT EXISTS(SELECT 1 FROM copilot_btw b WHERE b.conversation_id=copilot_conversation.id) ORDER BY (id=exercise_id) DESC,created_at,id LIMIT 100")
    List<CopilotConversationResponse> list(String exercise);
    @Select("SELECT " + COLUMNS + " FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    CopilotConversationResponse receipt(String exercise, String key);
    @Select("SELECT " + COLUMNS + " FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid AND id=#{id}::uuid")
    CopilotConversationResponse find(String exercise, String id);
    @Select("SELECT count(*) FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid AND NOT EXISTS(SELECT 1 FROM copilot_btw b WHERE b.conversation_id=copilot_conversation.id)")
    int count(String exercise);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_btw WHERE conversation_id=#{conversation}::uuid)")
    boolean isBtw(String conversation);
    @Insert("INSERT INTO copilot_conversation(id,exercise_id,request_key,created_at) VALUES(#{exercise}::uuid,#{exercise}::uuid,'initial',#{now}) ON CONFLICT(id) DO NOTHING")
    void ensureInitial(String exercise, Instant now);
    @Insert("INSERT INTO copilot_conversation(id,exercise_id,request_key,created_at) VALUES(#{id}::uuid,#{exercise}::uuid,#{key},#{now})")
    void insert(String id, String exercise, String key, Instant now);
    @Insert("INSERT INTO copilot_conversation(id,exercise_id,request_key,created_at,fork_source,fork_message) VALUES(#{id}::uuid,#{exercise}::uuid,#{key},#{now},#{source}::uuid,#{message}::uuid)")
    void insertFork(String id, String exercise, String key, Instant now, String source, String message);
    @Select("SELECT " + CopilotTaskRepository.MESSAGE + " FROM copilot_conversation_message WHERE conversation_id=#{source}::uuid AND id=#{message}::uuid")
    CopilotMessageEntity boundary(String source, String message);
    @Select("SELECT count(*) FROM copilot_conversation_message WHERE conversation_id=#{source}::uuid AND sequence<=#{through}")
    int forkCount(String source, long through);
    @Select("SELECT coalesce(sum(octet_length(payload)),0)::bigint FROM copilot_conversation_message WHERE conversation_id=#{source}::uuid AND sequence<=#{through}")
    long forkBytes(String source, long through);
    @Select("WITH candidates AS (SELECT " + CopilotTaskRepository.MESSAGE + " FROM copilot_conversation_message WHERE conversation_id=#{source}::uuid AND sequence<=#{through} ORDER BY sequence LIMIT 2001), budgeted AS (SELECT *,sum(octet_length(payload)) OVER (ORDER BY sequence) AS bytes FROM candidates) SELECT " + CopilotTaskRepository.MESSAGE + " FROM budgeted WHERE bytes<=16777216 ORDER BY sequence")
    List<CopilotMessageEntity> forkMessages(String source, long through);
    @Insert("INSERT INTO copilot_fork_message(conversation_id,exercise_id,message_id) SELECT #{id}::uuid,#{exercise}::uuid,id FROM copilot_conversation_message WHERE conversation_id=#{source}::uuid AND sequence<=#{through}")
    int inherit(String id, String exercise, String source, long through);
}
