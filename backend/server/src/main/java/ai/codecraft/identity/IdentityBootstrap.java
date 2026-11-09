package ai.codecraft.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public final class IdentityBootstrap implements ApplicationRunner {
    private final IdentityStore store;
    private final IdentitySecrets secrets;
    private final IdentitySettings settings;

    public IdentityBootstrap(IdentityStore store, IdentitySecrets secrets, IdentitySettings settings) {
        this.store = store;
        this.secrets = secrets;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (store.hasAdministrator()) return;
        try {
            String username = IdentityPolicy.username(settings.bootstrapUsername());
            String hash = secrets.hash(settings.bootstrapPassword());
            store.bootstrap(username, hash);
        } catch (IdentityFailure failure) {
            throw new IllegalStateException("A valid bootstrap administrator is required for an empty database");
        }
    }
}
