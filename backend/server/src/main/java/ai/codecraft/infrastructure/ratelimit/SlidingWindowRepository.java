package ai.codecraft.infrastructure.ratelimit;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SlidingWindowRepository {
    @Update("SELECT pg_advisory_xact_lock(593284157)")
    void lock();

    @Delete("DELETE FROM sliding_window_rate_limit WHERE expires_at <= #{now}")
    void expire(Instant now);

    @Select("SELECT count(DISTINCT bucket_hash) FROM sliding_window_rate_limit")
    int buckets();

    @Select("<script>SELECT bucket_hash, count(*) AS attempts FROM sliding_window_rate_limit "
        + "WHERE bucket_hash IN <foreach collection='buckets' item='bucket' open='(' separator=',' close=')'>"
        + "#{bucket}</foreach> GROUP BY bucket_hash</script>")
    List<RateLimitBucketCount> attempts(@Param("buckets") List<String> buckets);

    @Insert("<script>INSERT INTO sliding_window_rate_limit(bucket_hash, expires_at) VALUES "
        + "<foreach collection='charges' item='charge' separator=','>"
        + "(#{charge.bucketHash}, #{charge.expiresAt})</foreach></script>")
    void charge(@Param("charges") List<RateLimitCharge> charges);
}
