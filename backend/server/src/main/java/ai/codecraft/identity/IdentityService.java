package ai.codecraft.identity;

import org.springframework.stereotype.Service;

@Service
public final class IdentityService {
    public record Login(String token, IdentityStore.Identity identity) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    private final IdentityStore store;
    private final IdentitySecrets secrets;

    public IdentityService(IdentityStore store, IdentitySecrets secrets) {
        this.store = store;
        this.secrets = secrets;
    }

    public Login login(String usernameInput, String password, String address, String anonymousHash,
                       String expectedCsrf, String previousSessionHash) {
        String username;
        try { username = IdentityPolicy.username(usernameInput); }
        catch (IdentityFailure failure) {
            rate("login", "<invalid-account>", address);
            throw failure;
        }
        rate("login", username, address);
        IdentityPolicy.password(password);
        IdentityMapper.User user = store.findUser(username);
        boolean matches = secrets.matches(password, user == null ? null : user.passwordHash());
        if (!matches || user == null) throw new IdentityFailure(401, "INVALID_CREDENTIALS");
        String token = secrets.token();
        IdentityStore.Identity identity = store.login(user, IdentitySecrets.digest(token), secrets.token(),
            anonymousHash, expectedCsrf, previousSessionHash);
        return new Login(token, identity);
    }

    public void changePassword(String sessionHash, String currentPassword, String newPassword, String key, String address) {
        IdentityPolicy.idempotencyKey(key);
        IdentityStore.Authenticated auth = store.authenticate(sessionHash);
        rate("password", auth.user().username(), address);
        IdentityPolicy.password(currentPassword);
        IdentityPolicy.password(newPassword);
        if (!secrets.matches(currentPassword, auth.user().passwordHash())) {
            throw new IdentityFailure(400, "INVALID_CURRENT_PASSWORD");
        }
        store.changePassword(sessionHash, auth.user(), secrets.hash(newPassword), key,
            secrets.fingerprint(currentPassword, newPassword));
    }

    public IdentityStore.Created createUser(String sessionHash, String usernameInput, String key) {
        String username = IdentityPolicy.username(usernameInput);
        IdentityPolicy.idempotencyKey(key);
        String password = secrets.token();
        return store.createUser(sessionHash, username, password, secrets.hash(password), key, secrets.fingerprint(username));
    }

    private void rate(String action, String account, String address) {
        if (!store.admit(secrets.fingerprint(action, "account", account), secrets.fingerprint(action, "address", address))) {
            throw new IdentityFailure(429, "RATE_LIMITED");
        }
    }
}
