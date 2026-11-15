package ai.codecraft.modules.modelaccess.repository;

import ai.codecraft.modules.modelaccess.model.entity.ModelCheckEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckAuthorityEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckRequestEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckReuseEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckResultEntity;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import org.apache.ibatis.annotations.Update;
import java.time.Instant;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCostSubtotalResponse;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModelCheckRepository {
    @Select("""
        SELECT 'CHECK'::text AS purpose,count(*)::text AS calls,count(*) FILTER(WHERE usage_facts->>'complete'='true')::text AS complete_calls,
          sum((usage_facts->>'inputTokens')::numeric)::text AS input_tokens,
          sum((usage_facts->>'ordinaryInputTokens')::numeric)::text AS ordinary_input_tokens,
          sum((usage_facts->>'cacheReadTokens')::numeric)::text AS cache_read_tokens,
          sum((usage_facts->>'cacheWriteTokens')::numeric)::text AS cache_write_tokens,
          sum((usage_facts->>'outputTokens')::numeric)::text AS output_tokens,
          sum((usage_facts->>'totalTokens')::numeric)::text AS total_tokens,count(estimated_cost)::text AS priced_calls
        FROM personal_model_check_request WHERE check_id=#{check}::uuid
        """)
    List<ModelUsageTotalsResponse> usageTotals(String check);
    @Select("""
        SELECT 'CHECK'::text AS purpose,cost_currency AS currency,sum(estimated_cost)::text AS amount,count(*)::text AS priced_calls
        FROM personal_model_check_request WHERE check_id=#{check}::uuid AND estimated_cost IS NOT NULL
        GROUP BY cost_currency ORDER BY cost_currency
        """)
    List<ModelCostSubtotalResponse> usageCosts(String check);

    @Update("UPDATE personal_model_check_request SET price_snapshot=#{price},configuration_payload=#{configuration} WHERE id=#{id}::uuid AND status='DISPATCHED'")
    void accountingSnapshot(String id, String price, String configuration);
    @Select("SELECT price_snapshot FROM personal_model_check_request WHERE id=#{id}::uuid")
    String priceSnapshot(String id);
    @Update("UPDATE personal_model_check_request SET usage_payload=#{raw},usage_facts=#{receipt.factsPayload}::jsonb,estimated_cost=#{receipt.amount},cost_currency=#{receipt.currency},price_reason=#{receipt.priceReason} WHERE id=#{id}::uuid AND usage_facts IS NULL")
    int accounting(String id, String raw, ModelUsageReceiptDTO receipt);
    @Select("SELECT c.id::text FROM personal_model_check c WHERE c.completed_at<=#{cutoff} AND c.status NOT IN ('QUEUED','RUNNING') AND NOT EXISTS (SELECT 1 FROM personal_model_check_reuse u JOIN personal_model_check_request r ON r.id=u.basic_request_id OR r.id=u.tool_request_id WHERE r.check_id=c.id AND u.check_id<>c.id) ORDER BY c.completed_at,c.id LIMIT 100")
    List<String> retentionCandidates(Instant cutoff);

    @Select("SELECT EXISTS(SELECT 1 FROM personal_model_check c WHERE c.id=#{id}::uuid AND c.completed_at<=#{cutoff} AND c.status NOT IN ('QUEUED','RUNNING') AND NOT EXISTS (SELECT 1 FROM personal_model_check_reuse u JOIN personal_model_check_request r ON r.id=u.basic_request_id OR r.id=u.tool_request_id WHERE r.check_id=c.id AND u.check_id<>c.id))")
    boolean retentionEligible(@Param("id") String id, @Param("cutoff") Instant cutoff);

    @Delete("DELETE FROM personal_model_check_reuse WHERE check_id=#{id}::uuid")
    void deleteReuse(String id);

    @Delete("DELETE FROM personal_model_check_request WHERE check_id=#{id}::uuid")
    void deleteRequests(String id);

    @Delete("DELETE FROM personal_model_check_authority WHERE check_id=#{id}::uuid")
    void deleteAuthority(String id);

    @Delete("DELETE FROM personal_model_check WHERE id=#{id}::uuid")
    void deleteCheck(String id);
    @Select("SELECT id::text FROM personal_model_check WHERE user_id=#{owner}::uuid AND model_configuration_id=#{model}::uuid ORDER BY started_at DESC,id DESC LIMIT 1")
    String latestCheck(@Param("owner") String owner, @Param("model") String model);

    @Select("SELECT id::text FROM personal_model_check WHERE status='QUEUED' ORDER BY started_at,id LIMIT 100")
    List<String> queued();

    @Select("SELECT r.id::text,r.ordinal,r.status,r.completed_at,r.error_code,(r.check_id<>#{id}::uuid) AS reused FROM personal_model_check_request r WHERE r.check_id=#{id}::uuid OR r.id IN (SELECT basic_request_id FROM personal_model_check_reuse WHERE check_id=#{id}::uuid UNION SELECT tool_request_id FROM personal_model_check_reuse WHERE check_id=#{id}::uuid)")
    List<ModelCheckResultEntity> results(String id);

    @Select("SELECT reason FROM personal_model_check WHERE id=#{id}::uuid")
    String reason(String id);

    @Select("SELECT r.id::text FROM personal_model_check_request r JOIN personal_model_check c ON c.id=r.check_id WHERE c.user_id=#{owner}::uuid AND c.connection_id=#{reference.connectionId}::uuid AND c.model_configuration_id=#{reference.modelConfigurationId}::uuid AND c.connection_configuration_version=#{reference.connectionConfigurationVersion} AND c.model_configuration_version=#{reference.modelConfigurationVersion} AND r.ordinal=#{ordinal} AND r.status='SUCCEEDED' AND r.completed_at>#{since} ORDER BY r.completed_at DESC,r.id DESC LIMIT 1")
    String reusable(@Param("owner") String owner, @Param("reference") ModelInvocationReferenceDTO reference, @Param("ordinal") int ordinal, @Param("since") Instant since);

    @Insert("INSERT INTO personal_model_check_reuse(check_id,basic_request_id,tool_request_id) VALUES(#{checkId}::uuid,#{basicRequestId}::uuid,#{toolRequestId}::uuid)")
    void insertReuse(ModelCheckReuseEntity value);

    @Select("SELECT check_id::text,basic_request_id::text,tool_request_id::text FROM personal_model_check_reuse WHERE check_id=#{id}::uuid")
    ModelCheckReuseEntity reuse(String id);

    @Select("SELECT id::text,user_id::text,connection_id::text,model_configuration_id::text,connection_configuration_version,model_configuration_version,status,started_at FROM personal_model_check WHERE id=#{id}::uuid")
    ModelCheckEntity find(String id);

    @Insert("INSERT INTO personal_model_check_authority(check_id,user_id,session_hash,auth_version,not_after) VALUES(#{checkId}::uuid,#{userId}::uuid,#{sessionHash},#{authVersion},#{notAfter})")
    void insertAuthority(ModelCheckAuthorityEntity value);

    @Select("SELECT check_id::text,user_id::text,session_hash,auth_version,not_after,worker_id::text FROM personal_model_check_authority WHERE check_id=#{id}::uuid")
    ModelCheckAuthorityEntity authority(String id);

    @Update("UPDATE personal_model_check_authority SET worker_id=#{worker}::uuid WHERE check_id=#{id}::uuid AND worker_id IS NULL")
    int claim(@Param("id") String id, @Param("worker") String worker);

    @Select("SELECT id::text,check_id::text,ordinal,worker_id::text,status,dispatched_at,not_after,decoded_bytes FROM personal_model_check_request WHERE check_id=#{id}::uuid ORDER BY ordinal DESC LIMIT 1")
    ModelCheckRequestEntity latest(String id);

    @Select("SELECT id::text,check_id::text,ordinal,worker_id::text,status,dispatched_at,not_after,decoded_bytes FROM personal_model_check_request WHERE id=#{id}::uuid")
    ModelCheckRequestEntity request(String id);

    @Update("UPDATE personal_model_check_request SET status=#{status},decoded_bytes=#{bytes},completed_at=#{now},error_code=#{error} WHERE id=#{id}::uuid AND status='DISPATCHED'")
    int completeRequest(@Param("id") String id, @Param("status") String status, @Param("bytes") int bytes, @Param("now") Instant now, @Param("error") String error);

    @Select("SELECT c.id::text FROM personal_model_check c LEFT JOIN personal_model_check_authority a ON a.check_id=c.id LEFT JOIN LATERAL (SELECT not_after FROM personal_model_check_request WHERE check_id=c.id ORDER BY ordinal DESC LIMIT 1) r ON true WHERE c.status IN ('QUEUED','RUNNING') AND (a.check_id IS NULL OR a.not_after<=#{now} OR r.not_after<=#{now}) ORDER BY c.started_at,c.id LIMIT 100")
    List<String> expired(Instant now);

    @Select("SELECT COALESCE(sum(decoded_bytes),0) FROM personal_model_check_request WHERE check_id=#{id}::uuid")
    int decodedBytes(String id);

    @Insert("INSERT INTO personal_model_check_request(id,check_id,ordinal,worker_id,status,dispatched_at,not_after,decoded_bytes) VALUES(#{id}::uuid,#{checkId}::uuid,#{ordinal},#{workerId}::uuid,#{status},#{dispatchedAt},#{notAfter},#{decodedBytes})")
    void insertRequest(ModelCheckRequestEntity value);

    @Update("UPDATE personal_model_check SET status=#{status},reason=#{reason},completed_at=CASE WHEN #{status} IN ('QUEUED','RUNNING') THEN NULL ELSE clock_timestamp() END WHERE id=#{id}::uuid AND status IN ('QUEUED','RUNNING')")
    int transition(@Param("id") String id, @Param("status") String status, @Param("reason") String reason);

    @Insert("INSERT INTO personal_model_check(id,user_id,connection_id,model_configuration_id,connection_configuration_version,model_configuration_version,status,started_at,completed_at) VALUES(#{id}::uuid,#{userId}::uuid,#{connectionId}::uuid,#{modelConfigurationId}::uuid,#{connectionConfigurationVersion},#{modelConfigurationVersion},#{status},#{startedAt},CASE WHEN #{status} IN ('QUEUED','RUNNING') THEN NULL ELSE clock_timestamp() END)")
    void insert(ModelCheckEntity value);

    @Select("SELECT EXISTS(SELECT 1 FROM personal_model_check WHERE user_id=#{owner}::uuid AND status IN ('QUEUED','RUNNING'))")
    boolean active(String owner);

    @Select("SELECT count(*) FROM personal_model_check WHERE user_id=#{owner}::uuid AND started_at>#{after}")
    int startsSince(@Param("owner") String owner, @Param("after") Instant after);
}
