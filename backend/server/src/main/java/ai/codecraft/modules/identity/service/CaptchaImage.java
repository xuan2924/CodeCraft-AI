package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.dto.CaptchaImageDTO;
import com.wf.captcha.SpecCaptcha;
import org.springframework.stereotype.Component;

@Component
public class CaptchaImage {
    public CaptchaImageDTO generate() {
        SpecCaptcha captcha = new SpecCaptcha(160, 56, 5);
        return new CaptchaImageDTO(captcha.text(), captcha.toBase64());
    }
}
