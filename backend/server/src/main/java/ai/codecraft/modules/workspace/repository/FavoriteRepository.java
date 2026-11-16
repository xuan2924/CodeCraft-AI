package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.FavoriteEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FavoriteRepository {
    @Select("SELECT f.problem_id, f.title, EXISTS(SELECT 1 FROM problem_version p WHERE p.problem_id = f.problem_id AND p.available) AS available "
        + "FROM problem_favorite f WHERE f.user_id = #{user}::uuid ORDER BY f.problem_id LIMIT #{limit}")
    List<FavoriteEntity> list(String user, int limit);

    @Select("SELECT count(*) FROM problem_favorite WHERE user_id = #{user}::uuid")
    int count(String user);

    @Select("SELECT EXISTS(SELECT 1 FROM problem_favorite WHERE user_id = #{user}::uuid AND problem_id = #{problem})")
    boolean exists(String user, String problem);

    @Insert("INSERT INTO problem_favorite(user_id, problem_id, title) "
        + "SELECT #{user}::uuid, problem_id, coalesce(artifact->'manifest'->>'title', problem_id) "
        + "FROM problem_version WHERE problem_id = #{problem} ORDER BY available DESC, version DESC LIMIT 1 "
        + "ON CONFLICT(user_id, problem_id) DO NOTHING")
    int add(String user, String problem);

    @Delete("DELETE FROM problem_favorite WHERE user_id = #{user}::uuid AND problem_id = #{problem}")
    void remove(String user, String problem);
}
