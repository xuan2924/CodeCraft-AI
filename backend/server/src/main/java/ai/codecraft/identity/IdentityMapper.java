package ai.codecraft.identity;

import java.time.Instant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IdentityMapper {
    record User(String id, String username, String passwordHash, String role,
                boolean passwordChangeRequired, long authVersion) {
        @Override public String toString() { return "User[redacted]"; }
    }
    record Session(String tokenHash, String userId, long authVersion, String csrfToken,
                   Instant issuedAt, Instant lastActivity, boolean revoked) {
        @Override public String toString() { return "Session[redacted]"; }
    }
    record Anonymous(String csrfToken, Instant expiresAt) {
        @Override public String toString() { return "Anonymous[redacted]"; }
    }
    record Receipt(String fingerprint, String operationId, String resultUserId) { }

    @Update("SELECT pg_advisory_xact_lock(716007)")
    void admissionLock();

    @Select("SELECT count(*) FROM identity_user WHERE role = 'ADMIN'")
    int administrators();

    @Select("SELECT id::text, username, password_hash, role, password_change_required, auth_version "
        + "FROM identity_user WHERE username = #{username}")
    User userByName(String username);

    @Select("SELECT id::text, username, password_hash, role, password_change_required, auth_version "
        + "FROM identity_user WHERE id = CAST(#{id} AS uuid) FOR UPDATE")
    User lockUser(String id);

    @Insert("INSERT INTO identity_user(id, username, password_hash, role) "
        + "VALUES(CAST(#{id} AS uuid), #{username}, #{hash}, #{role}) ON CONFLICT (username) DO NOTHING")
    int createUser(String id, String username, String hash, String role);

    @Select("SELECT token_hash, user_id::text, auth_version, csrf_token, issued_at, last_activity, revoked "
        + "FROM identity_session WHERE token_hash = #{hash}")
    Session session(String hash);

    @Select("SELECT id::text, username, password_hash, role, password_change_required, auth_version "
        + "FROM identity_user WHERE id = CAST(#{id} AS uuid)")
    User user(String id);

    @Insert("INSERT INTO identity_session(token_hash, user_id, auth_version, csrf_token, issued_at, last_activity) "
        + "VALUES(#{hash}, CAST(#{userId} AS uuid), #{version}, #{csrf}, #{now}, #{now})")
    void createSession(String hash, String userId, long version, String csrf, Instant now);

    @Update("UPDATE identity_session SET last_activity = GREATEST(last_activity, #{now}) "
        + "WHERE token_hash = #{hash} AND NOT revoked AND auth_version = #{version} "
        + "AND last_activity > CAST(#{now} AS timestamptz) - interval '7 days' "
        + "AND issued_at > CAST(#{now} AS timestamptz) - interval '30 days'")
    int renew(String hash, long version, Instant now);

    @Update("UPDATE identity_session SET csrf_token = #{csrf} WHERE token_hash = #{hash}")
    void rotateCsrf(String hash, String csrf);

    @Update("UPDATE identity_session SET revoked = true WHERE token_hash = #{hash}")
    void revokeSession(String hash);

    @Update("UPDATE identity_session SET revoked = true WHERE user_id = CAST(#{id} AS uuid)")
    void revokeAll(String id);

    @Update("UPDATE identity_user SET password_hash = #{hash}, password_change_required = #{required}, "
        + "auth_version = auth_version + 1 WHERE id = CAST(#{id} AS uuid)")
    void password(String id, String hash, boolean required);

    @Select("SELECT csrf_token, expires_at FROM identity_anonymous_csrf WHERE token_hash = #{hash}")
    Anonymous anonymous(String hash);

    @Delete("DELETE FROM identity_anonymous_csrf WHERE expires_at <= #{now}")
    void expireAnonymous(Instant now);

    @Select("SELECT count(*) FROM identity_anonymous_csrf")
    int anonymousCount();

    @Insert("INSERT INTO identity_anonymous_csrf VALUES(#{hash}, #{csrf}, #{expires})")
    void createAnonymous(String hash, String csrf, Instant expires);

    @Delete("DELETE FROM identity_anonymous_csrf WHERE token_hash = #{hash}")
    void deleteAnonymous(String hash);

    @Delete("DELETE FROM identity_rate_limit WHERE window_start <= CAST(#{now} AS timestamptz) - interval '1 minute'")
    void expireRates(Instant now);

    @Select("SELECT count(*) FROM identity_rate_limit")
    int rateCount();

    @Select("SELECT COALESCE((SELECT attempts FROM identity_rate_limit WHERE bucket_hash = #{hash}), 0)")
    int attempts(String hash);

    @Insert("INSERT INTO identity_rate_limit VALUES(#{hash}, #{now}, 1) "
        + "ON CONFLICT (bucket_hash) DO UPDATE SET attempts = identity_rate_limit.attempts + 1")
    void attempt(String hash, Instant now);

    @Select("SELECT fingerprint, operation_id::text, result_user_id::text FROM identity_receipt "
        + "WHERE actor_id = CAST(#{actor} AS uuid) AND action = #{action} "
        + "AND target = #{target} AND idempotency_key = #{key}")
    Receipt receipt(String actor, String action, String target, String key);

    @Insert("INSERT INTO identity_receipt VALUES(CAST(#{actor} AS uuid), #{action}, #{target}, #{key}, "
        + "#{fingerprint}, CAST(#{operation} AS uuid), CAST(#{result} AS uuid), #{now})")
    void receiptInsert(String actor, String action, String target, String key, String fingerprint,
                       String operation, String result, Instant now);
}
