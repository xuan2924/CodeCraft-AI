package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.entity.CaptchaEntity;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.repository.CaptchaRepository;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaptchaStore {
    private final CaptchaRepository mapper;
    private final IdentityRepository identity;
    private final Clock clock;

    public CaptchaStore(CaptchaRepository mapper, IdentityRepository identity, Clock clock) {
        this.mapper = mapper;
        this.identity = identity;
        this.clock = clock;
    }

    @Transactional
    public void replace(String context, String id, String answer, Instant expires) {
        identity.admissionLock();
        mapper.expire(clock.instant());
        if (mapper.count() >= IdentityPolicy.ANONYMOUS_CAPACITY) throw new IdentityFailure(IdentityErrorCode.AUTH_BUSY);
        mapper.replace(context, id, answer, expires);
    }

    /** Return rather than throw: invalid attempts must still commit one-time consumption. */
    @Transactional
    public CaptchaEntity consume(String context, String id) {
        return mapper.consume(context, id);
    }
}
