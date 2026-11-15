package ai.codecraft.modules.skills.repository;

import ai.codecraft.modules.skills.model.dto.SkillSnapshotDTO;
import ai.codecraft.modules.skills.model.dto.SkillDocumentDTO;
import ai.codecraft.modules.skills.model.dto.SkillSnapshotSourceDTO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkillSnapshotRepository {
    @Select("SELECT s.id::text AS skill_id,v.id::text AS version_id,v.name,v.description,false AS explicitly_selected,false AS explicit_loaded FROM personal_skill s JOIN personal_skill_version v ON v.id=s.current_version_id WHERE s.user_id=#{owner}::uuid AND s.enabled AND NOT s.deleted ORDER BY s.id")
    List<SkillSnapshotDTO> enabled(String owner);
    @Select("SELECT coalesce(sum(1024 + CASE WHEN EXISTS(SELECT 1 FROM copilot_skill_snapshot r WHERE r.exercise_id=#{exercise}::uuid AND r.version_id=v.id) THEN 0 ELSE octet_length(v.files_payload)::bigint+8192 END),0) FROM personal_skill s JOIN personal_skill_version v ON v.id=s.current_version_id WHERE s.user_id=#{owner}::uuid AND s.enabled AND NOT s.deleted")
    long additionalBytes(String owner, String exercise);
    @Insert("<script>INSERT INTO copilot_skill_snapshot(task_id,exercise_id,user_id,skill_id,version_id,explicitly_selected) VALUES <foreach collection='snapshots' item='s' separator=','>(#{task}::uuid,#{exercise}::uuid,#{owner}::uuid,#{s.skillId}::uuid,#{s.versionId}::uuid,#{s.explicitlySelected})</foreach></script>")
    void pin(String owner, String task, String exercise, List<SkillSnapshotDTO> snapshots);
    @Select("SELECT r.skill_id::text,v.id::text AS version_id,v.name,v.description,r.explicitly_selected,r.explicit_loaded_at IS NOT NULL AS explicit_loaded FROM copilot_skill_snapshot r JOIN personal_skill_version v ON v.id=r.version_id AND v.user_id=r.user_id WHERE r.task_id=#{task}::uuid AND r.user_id=#{owner}::uuid ORDER BY r.skill_id")
    List<SkillSnapshotDTO> list(String owner, String task);
    @Select("SELECT v.id::text AS version_id,v.files_payload FROM copilot_skill_snapshot r JOIN personal_skill_version v ON v.id=r.version_id AND v.user_id=r.user_id WHERE r.task_id=#{task}::uuid AND r.user_id=#{owner}::uuid AND r.skill_id=#{skill}::uuid")
    SkillSnapshotSourceDTO source(String owner, String task, String skill);
    @Select("""
        WITH documents AS (
          SELECT r.skill_id::text,v.id::text AS version_id,f->>'content' AS content
          FROM copilot_skill_snapshot r JOIN personal_skill_version v ON v.id=r.version_id AND v.user_id=r.user_id,
            jsonb_array_elements(v.files_payload::jsonb->'files') f
          WHERE r.task_id=#{task}::uuid AND r.user_id=#{owner}::uuid AND r.explicitly_selected AND f->>'path'='SKILL.md'
        ) SELECT * FROM documents WHERE (SELECT coalesce(sum(octet_length(content)),0) FROM documents)<=#{maximum} ORDER BY skill_id
        """)
    List<SkillDocumentDTO> explicitDocuments(String owner, String task, long maximum);
    @Update("UPDATE copilot_skill_snapshot SET explicit_loaded_at=coalesce(explicit_loaded_at,#{now}) WHERE task_id=#{task}::uuid AND user_id=#{owner}::uuid AND explicitly_selected")
    void markExplicitLoaded(String owner, String task, Instant now);
    @Select("SELECT coalesce((SELECT sum(pg_column_size(r)) FROM copilot_skill_snapshot r WHERE r.exercise_id=#{exercise}::uuid),0) + coalesce((SELECT sum(pg_column_size(v)) FROM personal_skill_version v WHERE EXISTS(SELECT 1 FROM copilot_skill_snapshot r WHERE r.exercise_id=#{exercise}::uuid AND r.version_id=v.id)),0)")
    long retainedBytes(String exercise);
    @Delete("""
        WITH removed AS (DELETE FROM copilot_skill_snapshot WHERE exercise_id=#{exercise}::uuid RETURNING version_id)
        DELETE FROM personal_skill_version v WHERE v.id IN (SELECT version_id FROM removed)
          AND NOT EXISTS(SELECT 1 FROM personal_skill s WHERE s.current_version_id=v.id)
          AND NOT EXISTS(SELECT 1 FROM copilot_skill_snapshot r WHERE r.version_id=v.id AND r.exercise_id<>#{exercise}::uuid)
        """)
    void erase(String exercise);
}
