package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotApprovalChangeResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotApprovalChangeEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotApprovalModeEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotApprovalModeRepository {
    @Select("SELECT exercise_id::text,mode,version,updated_at FROM copilot_approval_mode WHERE exercise_id=#{exercise}::uuid")
    CopilotApprovalModeEntity current(String exercise);

    @Select("SELECT id::text,exercise_id::text,actor_id::text,auth_version,previous_mode,mode,version,request_key,fingerprint,created_at FROM copilot_approval_change WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    CopilotApprovalChangeEntity receipt(String exercise, String key);

    @Select("SELECT exercise_id::text,id::text,previous_mode,mode,version::text,actor_id::text,created_at FROM copilot_approval_change WHERE exercise_id=#{exercise}::uuid ORDER BY copilot_approval_change.version DESC LIMIT 20")
    List<CopilotApprovalChangeResponse> history(String exercise);

    @Insert("INSERT INTO copilot_approval_mode(exercise_id,mode,version,updated_at) VALUES(#{exerciseId}::uuid,#{mode},#{version},#{updatedAt}) ON CONFLICT(exercise_id) DO UPDATE SET mode=excluded.mode,version=excluded.version,updated_at=excluded.updated_at")
    void select(CopilotApprovalModeEntity mode);

    @Insert("INSERT INTO copilot_approval_change(id,exercise_id,actor_id,auth_version,previous_mode,mode,version,request_key,fingerprint,created_at) VALUES(#{id}::uuid,#{exerciseId}::uuid,#{actorId}::uuid,#{authVersion},#{previousMode},#{mode},#{version},#{requestKey},#{fingerprint},#{createdAt})")
    void audit(CopilotApprovalChangeEntity change);
}
