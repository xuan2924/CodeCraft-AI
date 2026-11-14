package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExerciseModelRepository {
    @Select("SELECT selected_connection_id::text AS connection_id, selected_model_id::text AS model_configuration_id, selected_connection_version AS connection_configuration_version, selected_model_version AS model_configuration_version FROM workspace_exercise WHERE id=#{id}::uuid AND selected_model_id IS NOT NULL")
    ModelInvocationReferenceDTO find(String id);

    @Update("UPDATE workspace_exercise SET selected_connection_id=#{reference.connectionId}::uuid, selected_model_id=#{reference.modelConfigurationId}::uuid, selected_connection_version=#{reference.connectionConfigurationVersion}, selected_model_version=#{reference.modelConfigurationVersion} WHERE id=#{id}::uuid")
    int select(String id, ModelInvocationReferenceDTO reference);
}
