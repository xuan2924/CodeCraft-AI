package ai.codecraft.fixture;

import ai.codecraft.modules.identity.model.dto.CaptchaImageDTO;
import ai.codecraft.modules.identity.service.CaptchaImage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-classpath-only rendering seam. Production has no fixed-answer mode. */
@TestConfiguration
public class CaptchaFixture {
    @Bean @Primary
    CaptchaImage fixtureCaptchaImage() {
        return new CaptchaImage() {
            @Override public CaptchaImageDTO generate() {
                return new CaptchaImageDTO("ABCDE", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jH1kAAAAASUVORK5CYII=");
            }
        };
    }
}
