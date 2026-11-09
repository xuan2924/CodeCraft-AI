package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.dto.AuthenticatedIdentityDTO;
import ai.codecraft.modules.identity.model.dto.CreatedAccountResponse;
import ai.codecraft.modules.identity.model.dto.LoginResultDTO;
import ai.codecraft.modules.identity.model.dto.PasswordResetResponse;
import ai.codecraft.modules.identity.model.dto.SessionResponse;
import ai.codecraft.modules.identity.model.entity.UserEntity;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;

import org.springframework.stereotype.Service;

@Service
public final class IdentityService {
    private final IdentityTransactionService store;
    private final IdentitySecrets secrets;

    public IdentityService(IdentityTransactionService store, IdentitySecrets secrets) {
        this.store = store;
        this.secrets = secrets;
    }

    public LoginResultDTO login(String usernameInput, String password, String address, String anonymousHash,
                       String expectedCsrf, String previousSessionHash) {
        String username;
        try { username = IdentityPolicy.username(usernameInput); }
        catch (IdentityFailure failure) {
            rate("login", "<invalid-account>", address);
            throw failure;
        }
        rate("login", username, address);
        IdentityPolicy.password(password);
        UserEntity user = store.findUser(username);
        boolean matches = secrets.matches(password, user == null ? null : user.passwordHash());
        if (!matches || user == null) throw new IdentityFailure(IdentityErrorCode.INVALID_CREDENTIALS);
        String token = secrets.token();
        SessionResponse identity = store.login(user, IdentitySecrets.digest(token), secrets.token(),
            anonymousHash, expectedCsrf, previousSessionHash);
        return new LoginResultDTO(token, identity);
    }

    public void changePassword(String sessionHash, String currentPassword, String newPassword, String key, String address) {
        IdentityPolicy.idempotencyKey(key);
        AuthenticatedIdentityDTO auth = store.authenticate(sessionHash);
        rate("password", auth.user().username(), address);
        IdentityPolicy.password(currentPassword);
        IdentityPolicy.password(newPassword);
        if (!secrets.matches(currentPassword, auth.user().passwordHash())) {
            throw new IdentityFailure(IdentityErrorCode.INVALID_CURRENT_PASSWORD);
        }
        store.changePassword(sessionHash, auth.user(), secrets.hash(newPassword), key,
            secrets.fingerprint(currentPassword, newPassword));
    }

    public CreatedAccountResponse createUser(String sessionHash, String usernameInput, String key) {
        String username = IdentityPolicy.username(usernameInput);
        IdentityPolicy.idempotencyKey(key);
        String password = secrets.token();
        return store.createUser(sessionHash, username, password, secrets.hash(password), key, secrets.fingerprint(username));
    }

    public PasswordResetResponse resetPassword(String sessionHash, String targetId, String key) {
        IdentityPolicy.idempotencyKey(key);
        AuthenticatedIdentityDTO auth = store.authenticate(sessionHash);
        if (auth.user().passwordChangeRequired()) throw new IdentityFailure(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
        if (!"ADMIN".equals(auth.user().role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        String password = secrets.token();
        return store.resetPassword(sessionHash, targetId, password, secrets.hash(password), key,
            secrets.fingerprint("RESET_PASSWORD", targetId));
    }

    private void rate(String action, String account, String address) {
        if (!store.admit(secrets.fingerprint(action, "account", account), secrets.fingerprint(action, "address", address))) {
            throw new IdentityFailure(IdentityErrorCode.RATE_LIMITED);
        }
    }
}
