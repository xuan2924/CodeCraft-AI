package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.dto.CaptchaResponse;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class CaptchaService {
    private static final Duration LIFETIME = Duration.ofMinutes(2);
    private final CaptchaStore store;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final CaptchaImage renderer;
    private final Clock clock;
    private final Semaphore renderSlots = new Semaphore(2);

    public CaptchaService(CaptchaStore store, IdentityTransactionService identity, IdentitySecrets secrets,
                          CaptchaImage renderer, Clock clock) {
        this.store = store;
        this.identity = identity;
        this.secrets = secrets;
        this.renderer = renderer;
        this.clock = clock;
    }

    public CaptchaResponse issue(String csrf, String address) {
        String context = context(csrf);
        if (!identity.admit(secrets.fingerprint("captcha-context", context), secrets.fingerprint("captcha-address", address))) {
            throw new IdentityFailure(IdentityErrorCode.RATE_LIMITED);
        }
        if (!renderSlots.tryAcquire()) throw new IdentityFailure(IdentityErrorCode.AUTH_BUSY);
        try {
            var image = renderer.generate();
            String id = secrets.token();
            var expires = clock.instant().plus(LIFETIME);
            store.replace(context, id, answerHash(id, image.answer()), expires);
            return new CaptchaResponse(id, image.dataUrl(), expires);
        } finally { renderSlots.release(); }
    }

    public void verify(String csrf, String id, String answer) {
        String context = context(csrf);
        var challenge = id == null || id.length() != 43 ? null : store.consume(context, id);
        if (challenge == null || !clock.instant().isBefore(challenge.expiresAt()) || answer == null || answer.length() > 16
            || !MessageDigest.isEqual(challenge.answerHash().getBytes(StandardCharsets.US_ASCII),
                answerHash(id, answer).getBytes(StandardCharsets.US_ASCII))) {
            throw new IdentityFailure(IdentityErrorCode.CAPTCHA_INVALID);
        }
    }

    private String answerHash(String id, String answer) {
        return secrets.fingerprint("captcha-answer", id, answer.strip().toUpperCase(Locale.ROOT));
    }

    private String context(String csrf) {
        if (csrf == null || csrf.isBlank()) throw new IdentityFailure(IdentityErrorCode.CSRF_REJECTED);
        return secrets.fingerprint("captcha-context", csrf);
    }
}
