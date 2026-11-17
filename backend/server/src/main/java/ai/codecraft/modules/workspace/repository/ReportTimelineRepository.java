package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.dto.ReportTimelineItemDTO;
import ai.codecraft.modules.workspace.model.dto.ReportHintUsageDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** Retained objective metadata only: never copies code, prompts or provider credentials. */
@Mapper
public interface ReportTimelineRepository {
    @Select("""
        SELECT timestamp,kind,title,detail,revision,source_id FROM (
          SELECT created_at AS timestamp,'INTERVIEWER_ASKED' AS kind,mode AS title,
            topic || ' · L' || hint_level || ' · ' || state AS detail,revision::text AS revision,id::text AS source_id
          FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT started_at,'IMPLEMENTATION_STARTED','开始实现',NULL,NULL,id::text
          FROM workspace_exercise WHERE id=#{exercise}::uuid AND started_at IS NOT NULL
          UNION ALL SELECT recorded_at,'WORKSPACE_CHANGED','代码变更 (r' || base_revision || ' → r' || result_revision || ')',
            source,result_revision::text,id::text FROM workspace_change WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT (snapshot->>'finishedAt')::timestamptz,'TEST_COMPLETED','受控测试完成',
            target || ' · ' || state,revision::text,id::text FROM workspace_test_task
            WHERE exercise_id=#{exercise}::uuid AND snapshot->>'finishedAt' IS NOT NULL
          UNION ALL SELECT entered_at,'CHALLENGE_ENTERED','进入挑战',NULL,entered_revision::text,operation_id::text
          FROM workspace_challenge WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT started_at,'EXERCISE_FROZEN','演练冻结',NULL,final_revision::text,id::text
          FROM workspace_finalization WHERE exercise_id=#{exercise}::uuid AND final_revision IS NOT NULL AND started_at IS NOT NULL
        ) events ORDER BY timestamp,kind,source_id
        """)
    List<ReportTimelineItemDTO> timeline(String exercise);
    @Select("SELECT count(*) FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid AND mode=#{mode} AND state='SUCCEEDED'")
    long successfulInteractions(String exercise, String mode);
    @Select("SELECT topic,max(hint_level) AS max_level FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid AND mode='HINT' AND state='SUCCEEDED' GROUP BY topic ORDER BY topic")
    List<ReportHintUsageDTO> hints(String exercise);
}
