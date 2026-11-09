package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.dto.AuthenticatedIdentityDTO;
import ai.codecraft.modules.identity.model.dto.CreatedAccountResponse;
import ai.codecraft.modules.identity.model.dto.PasswordResetResponse;
import ai.codecraft.modules.identity.model.dto.SessionResponse;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.entity.AnonymousCsrfEntity;
import ai.codecraft.modules.identity.model.entity.OperationReceiptEntity;
import ai.codecraft.modules.identity.model.entity.SessionEntity;
import ai.codecraft.modules.identity.model.entity.UserEntity;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import ai.codecraft.modules.identity.repository.RevocationIntentRepository;
import ai.codecraft.modules.identity.model.entity.RevocationIntentEntity;
import java.util.List;
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
    private final IdentityRevocationNotifier revocations;
    private final RevocationIntentRepository intents;
    private final IdentityRevocationBarrier barrier;

    public IdentityTransactionService(IdentityRepository mapper, Clock clock, IdentityRevocationNotifier revocations,
                                      RevocationIntentRepository intents, IdentityRevocationBarrier barrier) {
        this.mapper = mapper;
        this.clock = clock;
        this.revocations = revocations;
        this.intents = intents;
        this.barrier = barrier;
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

    @Transactional(readOnly = true)
    public SessionAuthorizationDTO authorization(String sessionHash) {
        AuthenticatedIdentityDTO auth = authenticateInside(sessionHash, false);
        return new SessionAuthorizationDTO(auth.user().id(), auth.user().role(), auth.user().passwordChangeRequired(),
            auth.user().authVersion(), auth.identity().expiresAt());
    }

    // Joins the caller's short transaction so mutations and identity revocation share the user lock.
    @Transactional
    public SessionAuthorizationDTO authorizeMutation(String sessionHash) {
        AuthenticatedIdentityDTO auth = authenticateInside(sessionHash, true);
        requireOpenAdmission(auth.user().id());
        if (auth.user().passwordChangeRequired()) throw new IdentityFailure(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
        return new SessionAuthorizationDTO(auth.user().id(), auth.user().role(), false,
            auth.user().authVersion(), auth.identity().expiresAt());
    }

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
        requireOpenAdmission(verified.id());
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
        requireOpenAdmission(auth.user().id());
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
    public PasswordResetResponse resetPassword(String sessionHash, String targetId, String password,
                                               String passwordHash, String key, String fingerprint) {
        SessionEntity initial = sessionHash == null ? null : mapper.session(sessionHash);
        if (initial == null) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
        // All two-user operations lock in UUID order, including administrators resetting each other.
        mapper.lockUsers(initial.userId(), targetId);
        AuthenticatedIdentityDTO auth = authenticateInside(sessionHash, false);
        requireAdministrator(auth);
        if (mapper.user(targetId) == null) throw new IdentityFailure(IdentityErrorCode.USER_NOT_FOUND);
        OperationReceiptEntity previous = mapper.receipt(auth.user().id(), "RESET_PASSWORD", targetId, key);
        if (previous != null) {
            checkFingerprint(previous, fingerprint);
            renew(auth);
            return new PasswordResetResponse(targetId, previous.operationId(), false, null, false);
        }
        String fencedOperation = finishRevocation(auth.user().id(), targetId, "RESET_PASSWORD", key, null, fingerprint);
        mapper.password(targetId, passwordHash, true);
        mapper.revokeAll(targetId);
        String operation = fencedOperation == null ? UUID.randomUUID().toString() : fencedOperation;
        mapper.receiptInsert(auth.user().id(), "RESET_PASSWORD", targetId, key, fingerprint,
            operation, targetId, clock.instant());
        boolean self = auth.user().id().equals(targetId);
        if (!self) renew(auth);
        revocations.afterCommit(targetId, null);
        return new PasswordResetResponse(targetId, operation, true, password, self);
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
        String fencedOperation = finishRevocation(auth.user().id(), auth.user().id(), "CHANGE_PASSWORD", key, null, fingerprint);
        mapper.password(auth.user().id(), passwordHash, false);
        mapper.revokeAll(auth.user().id());
        mapper.receiptInsert(auth.user().id(), "CHANGE_PASSWORD", auth.user().id(), key, fingerprint,
            fencedOperation == null ? UUID.randomUUID().toString() : fencedOperation, auth.user().id(), clock.instant());
        revocations.afterCommit(auth.user().id(), null);
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
            finishRevocation(session.userId(), session.userId(), "LOGOUT", sessionHash, sessionHash, null);
            mapper.revokeSession(sessionHash);
            revocations.afterCommit(session.userId(), sessionHash);
        }
        mapper.deleteAnonymous(anonymousHash);
    }

    @Transactional
    public RevocationIntentEntity beginRevocation(String sessionHash, String targetId, String kind, String key, String fingerprint) {
        SessionEntity initial = sessionHash == null ? null : mapper.session(sessionHash);
        if (initial == null) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
        String target = targetId == null ? initial.userId() : targetId;
        mapper.lockUsers(initial.userId(), target);
        AuthenticatedIdentityDTO auth = authenticateInside(sessionHash, false);
        if (kind.equals("RESET_PASSWORD")) requireAdministrator(auth);
        else if (!target.equals(auth.user().id())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        if (mapper.user(target) == null) throw new IdentityFailure(IdentityErrorCode.USER_NOT_FOUND);
        OperationReceiptEntity completed = mapper.receipt(auth.user().id(), kind, target, key);
        if (completed != null) { checkFingerprint(completed, fingerprint); return null; }
        RevocationIntentEntity previous = intents.receipt(auth.user().id(), target, kind, key);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint) || previous.state().equals("REJECTED")) {
                throw new IdentityFailure(IdentityErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return previous;
        }
        if (intents.pending(target) != null) throw new IdentityFailure(IdentityErrorCode.IDENTITY_UNAVAILABLE);
        String scope = kind.equals("LOGOUT") ? sessionHash : null;
        List<String> preparations = barrier.pendingPreparations(target, scope);
        if (preparations.isEmpty()) return null;
        RevocationIntentEntity intent = new RevocationIntentEntity(UUID.randomUUID().toString(), auth.user().id(), target,
            sessionHash, scope, kind, key, fingerprint, "PENDING", clock.instant(), String.join(",", preparations));
        intents.insert(intent);
        return intent;
    }

    @Transactional(readOnly = true)
    public List<RevocationIntentEntity> pendingRevocations() { return intents.pendingFences(); }

    @Transactional
    public void fenced(String id) {
        RevocationIntentEntity intent = intents.find(id);
        if (intent == null) return;
        mapper.lockUsers(intent.actorId(), intent.targetId());
        try {
            authenticateInside(intent.actorSessionHash(), false);
            intents.state(id, "FENCED");
        } catch (IdentityFailure failure) {
            if (failure.errorCode() != IdentityErrorCode.UNAUTHENTICATED) throw failure;
            // Expired initiators cannot leave another user's admission permanently closed.
            intents.state(id, "REJECTED");
        }
    }

    @Transactional(readOnly = true)
    public RevocationIntentEntity revocation(String sessionHash, String id) {
        AuthenticatedIdentityDTO auth = authenticateInside(sessionHash, false);
        RevocationIntentEntity intent = intents.find(id);
        if (intent == null) return null;
        if (!intent.actorId().equals(auth.user().id())) throw new IdentityFailure(IdentityErrorCode.USER_NOT_FOUND);
        if (auth.user().passwordChangeRequired() && !List.of("CHANGE_PASSWORD", "LOGOUT").contains(intent.kind())) {
            throw new IdentityFailure(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
        return intent;
    }

    private void requireOpenAdmission(String user) {
        if (intents.pending(user) != null) throw new IdentityFailure(IdentityErrorCode.IDENTITY_UNAVAILABLE);
    }

    private String finishRevocation(String actor, String target, String kind, String key, String scope, String fingerprint) {
        RevocationIntentEntity intent = intents.receipt(actor, target, kind, key);
        if (intent != null && fingerprint != null && !intent.fingerprint().equals(fingerprint)) {
            throw new IdentityFailure(IdentityErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (intent != null && intent.state().equals("FENCED")) {
            intents.state(intent.id(), "SUCCEEDED");
            return intent.id();
        }
        // Direct transactional callers also cannot bypass an in-flight preparation or another revocation.
        if (intents.pending(target) != null || !barrier.pendingPreparations(target, scope).isEmpty()) {
            throw new IdentityFailure(IdentityErrorCode.IDENTITY_UNAVAILABLE);
        }
        return null;
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
