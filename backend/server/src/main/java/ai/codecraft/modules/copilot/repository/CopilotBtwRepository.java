package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotBtwResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotBtwRepository {
    @Select("SELECT exercise_id::text,parent_conversation_id::text,conversation_id::text,created_at FROM copilot_btw WHERE parent_conversation_id=#{parent}::uuid")
    CopilotBtwResponse find(String parent);
    @Insert("INSERT INTO copilot_btw(parent_conversation_id,conversation_id,exercise_id,created_at) VALUES(#{parent}::uuid,#{conversation}::uuid,#{exercise}::uuid,#{now})")
    void insert(String parent, String conversation, String exercise, Instant now);
    @Select("SELECT " + CopilotTaskRepository.MESSAGE + " FROM (SELECT DISTINCT ON(kind) * FROM copilot_conversation_message WHERE conversation_id=#{parent}::uuid AND kind IN ('USER','ASSISTANT') ORDER BY kind,sequence DESC) projected ORDER BY sequence")
    List<CopilotMessageEntity> projection(String parent);
}
