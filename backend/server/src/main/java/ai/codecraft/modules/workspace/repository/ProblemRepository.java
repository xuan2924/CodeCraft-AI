package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProblemRepository {
    @Select("SELECT id::text, problem_id, version, package_sha256, execution_image_digest, platform, artifact::text FROM problem_version ORDER BY problem_id, version")
    List<ProblemVersionEntity> list();

    @Select("SELECT id::text, problem_id, version, package_sha256, execution_image_digest, platform, artifact::text FROM problem_version WHERE id = #{id}::uuid")
    ProblemVersionEntity find(String id);

    @Select("SELECT coalesce(jsonb_typeof(artifact->'manifest'->'execution') = 'object', false) FROM problem_version WHERE id = #{id}::uuid")
    boolean executionAvailable(String id);

    @Insert("""
        INSERT INTO problem_version(id, problem_id, version, package_sha256, execution_image_digest, platform, artifact)
        VALUES (#{id}::uuid, #{problemId}, #{version}, #{packageSha256}, #{executionImageDigest}, #{platform}, #{artifact}::jsonb)
        ON CONFLICT (problem_id, version) DO NOTHING
        """)
    int insert(ProblemVersionEntity version);

    @Select("""
        SELECT count(*) = 1 FROM problem_version
        WHERE problem_id = #{problemId} AND version = #{version} AND package_sha256 = #{packageSha256}
          AND execution_image_digest = #{executionImageDigest} AND platform = #{platform} AND artifact = #{artifact}::jsonb
        """)
    boolean matches(ProblemVersionEntity version);
}
