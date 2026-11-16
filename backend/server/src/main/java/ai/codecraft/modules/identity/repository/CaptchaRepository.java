package ai.codecraft.modules.identity.repository;

import ai.codecraft.modules.identity.model.entity.CaptchaEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CaptchaRepository {
    @Delete("DELETE FROM identity_captcha WHERE expires_at <= #{now}")
    void expire(Instant now);

    @Select("SELECT count(*) FROM identity_captcha")
    int count();

    @Insert("INSERT INTO identity_captcha(context_hash, id, answer_hash, expires_at) VALUES(#{context}, #{id}, #{answer}, #{expires}) "
        + "ON CONFLICT(context_hash) DO UPDATE SET id = EXCLUDED.id, answer_hash = EXCLUDED.answer_hash, expires_at = EXCLUDED.expires_at")
    void replace(String context, String id, String answer, Instant expires);

    @Select(value = "DELETE FROM identity_captcha WHERE context_hash = #{context} AND id = #{id} RETURNING answer_hash, expires_at",
        affectData = true)
    CaptchaEntity consume(String context, String id);
}
