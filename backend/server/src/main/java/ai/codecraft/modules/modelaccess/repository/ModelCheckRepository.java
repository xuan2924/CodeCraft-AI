package ai.codecraft.modules.modelaccess.repository;

import ai.codecraft.modules.modelaccess.model.entity.ModelCheckEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModelCheckRepository {
    @Insert("INSERT INTO personal_model_check(id,user_id,connection_id,model_configuration_id,connection_configuration_version,model_configuration_version,status,started_at) VALUES(#{id}::uuid,#{userId}::uuid,#{connectionId}::uuid,#{modelConfigurationId}::uuid,#{connectionConfigurationVersion},#{modelConfigurationVersion},#{status},#{startedAt})")
    void insert(ModelCheckEntity value);

    @Select("SELECT EXISTS(SELECT 1 FROM personal_model_check WHERE user_id=#{owner}::uuid AND status IN ('QUEUED','RUNNING'))")
    boolean active(String owner);

    @Select("SELECT count(*) FROM personal_model_check WHERE user_id=#{owner}::uuid AND started_at>#{after}")
    int startsSince(@Param("owner") String owner, @Param("after") Instant after);
}
