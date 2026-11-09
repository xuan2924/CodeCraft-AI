package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.dto.AuthenticatedIdentityDTO;
import ai.codecraft.modules.identity.model.dto.CreatedAccountResponse;
import ai.codecraft.modules.identity.model.dto.SessionResponse;
import ai.codecraft.modules.identity.model.entity.AnonymousCsrfEntity;
import ai.codecraft.modules.identity.model.entity.OperationReceiptEntity;
import ai.codecraft.modules.identity.model.entity.SessionEntity;
import ai.codecraft.modules.identity.model.entity.UserEntity;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityTransactionService {
    private final IdentityRepository mapper;
    private final Clock clock;

    public IdentityTransactionService(IdentityRepository mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean hasAdministrator() { return mapper.administrators() > 0; }

    @Transactional
    public void bootstrap(String username, String hash) {
        mapper.admissionLock();
        if (mapper.administrators() > 0) return;
        if (mapper.createUser(UUID.randomUUID().toString(), username, hash, "ADMIN") != 1) {
            throw new IllegalStateException("Bootstrap administrator name is unavailable");
        }
    }

    @Transactional(readOnly = true)
    public UserEntity findUser(String username) { return mapper.userByName(username); }

    @Transactional(readOnly = true)
    public AuthenticatedIdentityDTO authenticate(String sessionHash) { return authenticateInside(sessionHash, false); }

    private AuthenticatedIdentityDTO authenticateInside(String sessionHash, boolean lock) {
        if (sessionHash == null) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
        SessionEntity initial = mapper.session(sessionHash);
        if (initial == null) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
        UserEntity user = lock ? mapper.lockUser(initial.userId()) : mapper.user(initial.userId());
        // Re-read after acquiring the user lock: a revocation may have won while we waited.
        SessionEntity session = mapper.session(sessionHash);
        Instant now = clock.instant();
        if (user == null || session == null || session.revoked() || session.authVersion() != user.authVersion()
            || !now.isBefore(IdentityPolicy.expiresAt(session.issuedAt(), session.lastActivity()))) {
            throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
        }
        return new AuthenticatedIdentityDTO(user, session, new SessionResponse(user.id(), user.username(), user.role(),
            user.passwordChangeRequired(), IdentityPolicy.expiresAt(session.issuedAt(), session.lastActivity())));
    }

    @Transactional
    public SessionResponse login(UserEntity verified, String tokenHash, String csrf,
                          String anonymousHash, String expectedCsrf, String previousSessionHash) {
        // Serialize consumption of the pre-login CSRF context with its creation/deletion.
        mapper.admissionLock();
        UserEntity current = mapper.lockUser(verified.id());
        if (current == null || current.authVersion() != verified.authVersion()
            || !current.passwordHash().equals(verified.passwordHash())) {
            throw new IdentityFailure(IdentityErrorCode.INVALID_CREDENTIALS);
        }
        String actual = csrfInside(previousSessionHash, anonymousHash);
        if (!sameToken(actual, expectedCsrf)) throw new IdentityFailure(IdentityErrorCode.CSRF_REJECTED);
        Instant now = clock.instant();
        mapper.createSession(tokenHash, current.id(), current.authVersion(), csrf, now);
        mapper.deleteAnonymous(anonymousHash);
        return new SessionResponse(current.id(), current.username(), current.role(), current.passwordChangeRequired(),
            IdentityPolicy.expiresAt(now, now));
    }

    @Transactional(readOnly = true)
    public String csrf(String sessionHash, String anonymousHash) { return csrfInside(sessionHash, anonymousHash); }

    private String csrfInside(String sessionHash, String anonymousHash) {
        if (sessionHash != null) {
            try { return authenticateInside(sessionHash, false).session().csrfToken(); }
            catch (IdentityFailure failure) {
                if (failure.status() != 401) throw failure;
            }
        }
        AnonymousCsrfEntity anonymous = anonymousHash == null ? null : mapper.anonymous(anonymousHash);
        return anonymous != null && clock.instant().isBefore(anonymous.expiresAt()) ? anonymous.csrfToken() : null;
    }

    @Transactional
    public void anonymous(String hash, String csrf) {
        mapper.admissionLock();
        mapper.expireAnonymous(clock.instant());
        if (mapper.anonymousCount() >= IdentityPolicy.ANONYMOUS_CAPACITY) {
            throw new IdentityFailure(IdentityErrorCode.CSRF_CAPACITY);
        }
        mapper.createAnonymous(hash, csrf, clock.instant().plus(IdentityPolicy.ANONYMOUS_LIMIT));
    }

    @Transactional
    public boolean admit(String accountBucket, String addressBucket) {
        mapper.admissionLock();
        Instant now = clock.instant();
        mapper.expireRates(now);
        int account = mapper.attempts(accountBucket);
        int address = mapper.attempts(addressBucket);
        // A bounded admission table also rejects distinct-name/IP floods before hashing.
        int newBuckets = (account == 0 ? 1 : 0) + (address == 0 ? 1 : 0);
        if (mapper.rateCount() + newBuckets > IdentityPolicy.RATE_BUCKET_CAPACITY) return false;
        boolean accepted = account < IdentityPolicy.ATTEMPTS_PER_MINUTE && address < IdentityPolicy.ATTEMPTS_PER_MINUTE;
        if (account < IdentityPolicy.ATTEMPTS_PER_MINUTE) mapper.attempt(accountBucket, now);
        if (address < IdentityPolicy.ATTEMPTS_PER_MINUTE) mapper.attempt(addressBucket, now);
        return accepted;
    }

    @Transactional
    public CreatedAccountResponse createUser(String sessionHash, String username, String password, String passwordHash,
                                             String key, String fingerprint) {
        AuthenticatedIdentityDTO auth = authenticateInside(sessionHash, true);
        requireAdministrator(auth);
        OperationReceiptEntity previous = mapper.receipt(auth.user().id(), "CREATE_USER", "users", key);
        if (previous != null) {
            checkFingerprint(previous, fingerprint);
            renew(auth);
            return new CreatedAccountResponse(previous.resultUserId(), previous.operationId(), false, null);
        }
        String id = UUID.randomUUID().toString();
        if (mapper.createUser(id, username, passwordHash, "USER") != 1) {
            throw new IdentityFailure(IdentityErrorCode.USERNAME_EXISTS);
        }
        String operation = UUID.randomUUID().toString();
        mapper.receiptInsert(auth.user().id(), "CREATE_USER", "users", key, fingerprint, operation, id, clock.instant());
        renew(auth);
        return new CreatedAccountResponse(id, operation, true, password);
    }

    @Transactional
    public void changePassword(String sessionHash, UserEntity verified, String passwordHash,
                               String key, String fingerprint) {
        AuthenticatedIdentityDTO auth = authenticateInside(sessionHash, true);
        if (!auth.user().id().equals(verified.id()) || auth.user().authVersion() != verified.authVersion()
            || !auth.user().passwordHash().equals(verified.passwordHash())) {
            throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
        }
        OperationReceiptEntity previous = mapper.receipt(auth.user().id(), "CHANGE_PASSWORD", auth.user().id(), key);
        if (previous != null) {
            checkFingerprint(previous, fingerprint);
            return;
        }
        mapper.password(auth.user().id(), passwordHash, false);
        mapper.revokeAll(auth.user().id());
        mapper.receiptInsert(auth.user().id(), "CHANGE_PASSWORD", auth.user().id(), key, fingerprint,
            UUID.randomUUID().toString(), auth.user().id(), clock.instant());
    }

    @Transactional
    public void invalidateCsrf(String sessionHash, String anonymousHash, String replacement) {
        if (sessionHash != null) {
            SessionEntity session = mapper.session(sessionHash);
            if (session != null) {
                mapper.lockUser(session.userId());
                mapper.rotateCsrf(sessionHash, replacement);
            }
        }
        mapper.deleteAnonymous(anonymousHash);
    }

    @Transactional
    public void logout(String sessionHash, String anonymousHash) {
        if (sessionHash != null && mapper.session(sessionHash) != null) {
            SessionEntity session = mapper.session(sessionHash);
            mapper.lockUser(session.userId());
            mapper.revokeSession(sessionHash);
        }
        mapper.deleteAnonymous(anonymousHash);
    }

    private void renew(AuthenticatedIdentityDTO auth) {
        if (mapper.renew(auth.session().tokenHash(), auth.user().authVersion(), clock.instant()) != 1) {
            throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
        }
    }

    private static void requireAdministrator(AuthenticatedIdentityDTO auth) {
        if (auth.user().passwordChangeRequired()) throw new IdentityFailure(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
        if (!"ADMIN".equals(auth.user().role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
    }

    private static void checkFingerprint(OperationReceiptEntity previous, String fingerprint) {
        if (!previous.fingerprint().equals(fingerprint)) throw new IdentityFailure(IdentityErrorCode.IDEMPOTENCY_CONFLICT);
    }

    private static boolean sameToken(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
