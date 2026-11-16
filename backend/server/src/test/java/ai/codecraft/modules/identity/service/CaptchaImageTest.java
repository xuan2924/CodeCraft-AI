package ai.codecraft.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CaptchaImageTest {
    @Test
    void rendersAnImageWithoutExposingTheAnswerInDiagnostics() throws Exception {
        var image = new CaptchaImage().generate();
        assertThat(image.answer()).matches("[A-Za-z2-9]{5}");
        assertThat(image.toString()).doesNotContain(image.answer());
        var png = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.dataUrl().split(",", 2)[1])));
        assertThat(png.getWidth()).isEqualTo(160);
        assertThat(png.getHeight()).isEqualTo(56);
    }
}
