package ai.codecraft.modules.modelaccess.repository;

import ai.codecraft.modules.modelaccess.model.dto.EncryptedCredentialDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelCredentialRotationCandidateDTO;
import ai.codecraft.modules.modelaccess.model.entity.ModelCommandEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelConnectionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import java.time.Instant;

@Mapper
public interface ModelConnectionRepository {
    String COLUMNS = "id::text, user_id::text, name, base_url, protocol, preset, credential_key_id, credential_nonce, credential_ciphertext, version, configuration_version, created_at, updated_at, deleted_at";

    @Select("SELECT " + COLUMNS + " FROM personal_model_connection WHERE id=#{id}::uuid")
    ModelConnectionEntity find(String id);
    @Select("SELECT EXISTS(SELECT 1 FROM personal_model_connection WHERE user_id=#{owner}::uuid AND lower(name)=lower(#{name}) AND deleted_at IS NULL AND (#{except}::uuid IS NULL OR id<>#{except}::uuid))")
    boolean nameExists(@Param("owner") String owner, @Param("name") String name, @Param("except") String except);
    @Insert("INSERT INTO personal_model_connection(id,user_id,name,base_url,protocol,preset,credential_key_id,credential_nonce,credential_ciphertext,version,configuration_version,created_at,updated_at) VALUES (#{id}::uuid,#{userId}::uuid,#{name},#{baseUrl},#{protocol},#{preset},#{credentialKeyId},#{credentialNonce},#{credentialCiphertext},#{version},#{configurationVersion},#{createdAt},#{updatedAt})")
    void insert(ModelConnectionEntity value);
    @Select("SELECT " + COLUMNS + " FROM personal_model_connection WHERE user_id=#{owner}::uuid AND deleted_at IS NULL AND (#{after}::uuid IS NULL OR id>#{after}::uuid) ORDER BY id LIMIT 21")
    List<ModelConnectionEntity> list(@Param("owner") String owner, @Param("after") String after);
    @Update("UPDATE personal_model_connection SET name=#{value.name},base_url=#{value.baseUrl},protocol=#{value.protocol},preset=#{value.preset},credential_key_id=#{value.credentialKeyId},credential_nonce=#{value.credentialNonce},credential_ciphertext=#{value.credentialCiphertext},version=#{value.version},configuration_version=#{value.configurationVersion},updated_at=#{value.updatedAt},deleted_at=#{value.deletedAt} WHERE id=#{value.id}::uuid AND user_id=#{value.userId}::uuid AND deleted_at IS NULL AND version=#{expected}")
    int update(@Param("value") ModelConnectionEntity value, @Param("expected") long expected);
    @Update("UPDATE personal_model_configuration SET deleted_at=#{now},updated_at=#{now},version=version+1,configuration_version=configuration_version+1 WHERE connection_id=#{connection}::uuid AND user_id=#{owner}::uuid AND deleted_at IS NULL")
    void retireModels(@Param("owner") String owner, @Param("connection") String connection, @Param("now") Instant now);
    @Select("SELECT id::text,user_id::text FROM personal_model_connection WHERE deleted_at IS NULL AND credential_key_id<>#{active} AND (#{after}::uuid IS NULL OR id>#{after}::uuid) ORDER BY id LIMIT 100")
    List<ModelCredentialRotationCandidateDTO> rotationCandidates(@Param("active") String active, @Param("after") String after);
    @Update("UPDATE personal_model_connection SET credential_key_id=#{credential.keyId},credential_nonce=#{credential.nonce},credential_ciphertext=#{credential.ciphertext} WHERE id=#{id}::uuid AND user_id=#{owner}::uuid AND deleted_at IS NULL AND credential_key_id=#{original.keyId} AND credential_nonce=#{original.nonce} AND credential_ciphertext=#{original.ciphertext}")
    int rotateCredential(@Param("id") String id, @Param("owner") String owner, @Param("original") EncryptedCredentialDTO original, @Param("credential") EncryptedCredentialDTO credential);
    @Select("SELECT user_id::text,kind,scope,request_key,fingerprint,response::text,completed_at FROM personal_model_command WHERE user_id=#{owner}::uuid AND kind=#{kind} AND scope=#{scope} AND request_key=#{key}")
    ModelCommandEntity command(@Param("owner") String owner, @Param("kind") String kind, @Param("scope") String scope, @Param("key") String key);
    @Insert("INSERT INTO personal_model_command(user_id,kind,scope,request_key,fingerprint,response,completed_at) VALUES(#{userId}::uuid,#{kind},#{scope},#{requestKey},#{fingerprint},#{response}::jsonb,#{completedAt})")
    void insertCommand(ModelCommandEntity value);
    @Update("UPDATE personal_model_command SET response=NULL WHERE (user_id,kind,scope,request_key) IN (SELECT user_id,kind,scope,request_key FROM personal_model_command WHERE completed_at<=#{before} AND response IS NOT NULL ORDER BY completed_at LIMIT 1000)")
    void expireReceipts(Instant before);
}
