package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.dto.AdminProblemResponse;
import java.util.List;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProblemRepository {
    @Select("SELECT id::text, problem_id, version, package_sha256, execution_image_digest, platform, artifact::text FROM problem_version WHERE available ORDER BY problem_id, version")
    List<ProblemVersionEntity> list();

    @Select("SELECT id::text, problem_id, version, package_sha256, execution_image_digest, platform, artifact::text FROM problem_version WHERE id = #{id}::uuid")
    ProblemVersionEntity find(String id);

    @Select("SELECT id::text, artifact->'challenge'->'manifest'->>'id' AS problem_id, artifact->'challenge'->'manifest'->>'version' AS version, artifact->'receipt'->'challenge'->>'packageSha256' AS package_sha256, artifact->'receipt'->'challenge'->>'imageId' AS execution_image_digest, artifact->'receipt'->'challenge'->>'platform' AS platform, artifact::text FROM problem_version WHERE id=#{id}::uuid AND jsonb_typeof(artifact->'challenge')='object'")
    ProblemVersionEntity challenge(String id);

    @Select("SELECT available FROM problem_version WHERE id = #{id}::uuid FOR SHARE")
    Boolean availableForStart(String id);

    @Select("SELECT coalesce(artifact->'manifest'->>'language','JAVA_MAVEN') FROM problem_version WHERE id=#{id}::uuid")
    String language(String id);

    @Select("SELECT coalesce(artifact->'manifest'->>'language','JAVA_MAVEN')='JAVA_MAVEN' AND coalesce(jsonb_typeof(artifact->'manifest'->'execution') = 'object', false) FROM problem_version WHERE id = #{id}::uuid")
    boolean executionAvailable(String id);

    @Select("SELECT DISTINCT unnest(loaded_packages) FROM sandbox_node WHERE ready AND healthy_until>clock_timestamp()")
    List<String> loadedPackages();

    String ADMIN_FIELDS = "id::text,problem_id,version,coalesce(artifact->'manifest'->>'language','JAVA_MAVEN') AS language, "
        + "coalesce(artifact->'manifest'->>'language','JAVA_MAVEN')='JAVA_MAVEN' AS execution_supported,package_sha256,available";

    @Select("SELECT " + ADMIN_FIELDS + " FROM problem_version ORDER BY problem_id,version")
    List<AdminProblemResponse> adminList();

    @Select("SELECT id::text,problem_id,version,package_sha256 FROM problem_version WHERE problem_id=#{problem} AND version=#{version}")
    AdminProblemResponse.Published published(String problem, String version);

    @Insert("INSERT INTO problem_availability_receipt(actor_id,request_key,version_id,available) VALUES(#{actor}::uuid,#{key},#{id}::uuid,#{available}) ON CONFLICT DO NOTHING")
    int claimAvailability(String actor, String key, String id, boolean available);
    @Select("SELECT EXISTS(SELECT 1 FROM problem_availability_receipt WHERE actor_id=#{actor}::uuid AND request_key=#{key} AND version_id=#{id}::uuid AND available=#{available})")
    boolean sameAvailability(String actor, String key, String id, boolean available);

    @Update("UPDATE problem_version SET available=#{available} WHERE id=#{id}::uuid")
    int availability(String id, boolean available);

    @Insert("INSERT INTO problem_publish_receipt(actor_id,request_key,fingerprint) VALUES(#{actor}::uuid,#{key},#{fingerprint}) ON CONFLICT DO NOTHING")
    int claim(String actor, String key, String fingerprint);

    @Update("UPDATE problem_publish_receipt SET version_id=#{version}::uuid WHERE actor_id=#{actor}::uuid AND request_key=#{key} AND version_id IS NULL")
    void bind(String actor, String key, String version);

    @Select("SELECT p.id::text,p.problem_id,p.version,p.package_sha256 FROM problem_publish_receipt r JOIN problem_version p ON p.id=r.version_id WHERE r.actor_id=#{actor}::uuid AND r.request_key=#{key}")
    AdminProblemResponse.Published bound(String actor, String key);

    @Select("SELECT fingerprint FROM problem_publish_receipt WHERE actor_id=#{actor}::uuid AND request_key=#{key}")
    String fingerprint(String actor, String key);

    @Insert("""
        INSERT INTO problem_version(id, problem_id, version, package_sha256, execution_image_digest, platform, artifact)
        VALUES (#{id}::uuid, #{problemId}, #{version}, #{packageSha256}, #{executionImageDigest}, #{platform}, #{artifact}::jsonb)
        ON CONFLICT (problem_id, version) DO NOTHING
        """)
    int insert(ProblemVersionEntity version);

    @Select("""
        SELECT count(*) = 1 FROM problem_version
        WHERE problem_id = #{problemId} AND version = #{version} AND package_sha256 = #{packageSha256}
          AND execution_image_digest IS NOT DISTINCT FROM #{executionImageDigest} AND platform IS NOT DISTINCT FROM #{platform} AND artifact = #{artifact}::jsonb
        """)
    boolean matches(ProblemVersionEntity version);
}
