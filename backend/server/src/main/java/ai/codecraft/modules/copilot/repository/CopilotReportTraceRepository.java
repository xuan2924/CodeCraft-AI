package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotReportTraceDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotReportTraceRepository {
    @Select("""
        SELECT
          (SELECT coalesce(sum(reference_count),0) FROM copilot_task WHERE exercise_id=#{exercise}::uuid) AS reference_count,
          ((SELECT count(*) FROM copilot_tool WHERE exercise_id=#{exercise}::uuid AND name='load_skill'
            AND state='SUCCEEDED' AND outcome_code='OK') +
           (SELECT count(*) FROM copilot_skill_snapshot WHERE exercise_id=#{exercise}::uuid AND explicit_loaded_at IS NOT NULL)) AS skill_load_count,
          (SELECT count(*) FROM copilot_steer WHERE exercise_id=#{exercise}::uuid AND state='APPLIED') AS steer_count,
          (SELECT count(*) FROM copilot_task WHERE exercise_id=#{exercise}::uuid AND lane='BTW') AS btw_task_count
        """)
    CopilotReportTraceDTO trace(String exercise);
}
