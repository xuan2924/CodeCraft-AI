package ai.codecraft.modules.modelaccess.repository;

import ai.codecraft.modules.modelaccess.model.entity.ModelConfigurationEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ModelConfigurationRepository {
    String COLUMNS = "id::text,connection_id::text,user_id::text,name,model_id,context_window,max_output_tokens,thinking_mode,reasoning_effort,thinking_budget_tokens,temperature,version,configuration_version,created_at,updated_at,deleted_at";
    @Select("SELECT " + COLUMNS + " FROM personal_model_configuration WHERE id=#{id}::uuid")
    ModelConfigurationEntity find(String id);
    @Select("SELECT EXISTS(SELECT 1 FROM personal_model_configuration WHERE connection_id=#{connection}::uuid AND lower(name)=lower(#{name}) AND deleted_at IS NULL AND (#{except}::uuid IS NULL OR id<>#{except}::uuid))")
    boolean nameExists(@Param("connection") String connection, @Param("name") String name, @Param("except") String except);
    @Select("SELECT " + COLUMNS + " FROM personal_model_configuration WHERE connection_id=#{connection}::uuid AND user_id=#{owner}::uuid AND deleted_at IS NULL AND (#{after}::uuid IS NULL OR id>#{after}::uuid) ORDER BY id LIMIT 21")
    List<ModelConfigurationEntity> list(@Param("owner") String owner, @Param("connection") String connection, @Param("after") String after);
    @Insert("INSERT INTO personal_model_configuration(id,connection_id,user_id,name,model_id,context_window,max_output_tokens,thinking_mode,reasoning_effort,thinking_budget_tokens,temperature,version,configuration_version,created_at,updated_at) VALUES(#{id}::uuid,#{connectionId}::uuid,#{userId}::uuid,#{name},#{modelId},#{contextWindow},#{maxOutputTokens},#{thinkingMode},#{reasoningEffort},#{thinkingBudgetTokens},#{temperature},#{version},#{configurationVersion},#{createdAt},#{updatedAt})")
    void insert(ModelConfigurationEntity value);
    @Update("UPDATE personal_model_configuration SET name=#{value.name},model_id=#{value.modelId},context_window=#{value.contextWindow},max_output_tokens=#{value.maxOutputTokens},thinking_mode=#{value.thinkingMode},reasoning_effort=#{value.reasoningEffort},thinking_budget_tokens=#{value.thinkingBudgetTokens},temperature=#{value.temperature},version=#{value.version},configuration_version=#{value.configurationVersion},updated_at=#{value.updatedAt},deleted_at=#{value.deletedAt} WHERE id=#{value.id}::uuid AND user_id=#{value.userId}::uuid AND deleted_at IS NULL AND version=#{expected}")
    int update(@Param("value") ModelConfigurationEntity value, @Param("expected") long expected);
}
