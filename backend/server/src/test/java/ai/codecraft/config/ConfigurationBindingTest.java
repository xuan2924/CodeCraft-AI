package ai.codecraft.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.modules.identity.config.IdentitySettings;
import ai.codecraft.modules.modelaccess.config.ModelCredentialRotationProperties;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.status.config.StatusProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

@ExtendWith(OutputCaptureExtension.class)
class ConfigurationBindingTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({IdentitySettings.class, StatusProperties.class, ModelCredentialRotationProperties.class, ModelSecurityProperties.class})
    static class Bindings { }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Bindings.class)
        .withPropertyValues("identity.public-origin=https://example.test", "tracer.sandbox-port=9090");

    @Test
    void bindsImmutableSettingsAndDefaultsWithoutComponentOrValueInjection() {
        runner.withPropertyValues("identity.fingerprint-key=" + "01".repeat(32)).run(context -> {
            assertThat(context).hasNotFailed();
            IdentitySettings settings = context.getBean(IdentitySettings.class);
            assertThat(settings.secureCookie()).isTrue();
            assertThat(settings.bootstrapUsername()).isEmpty();
            assertThat(settings.bootstrapPassword()).isEmpty();
            byte[] copy = settings.fingerprintKey();
            copy[0] = 0;
            assertThat(settings.fingerprintKey()[0]).isEqualTo((byte) 1);
            assertThat(context.getBean(StatusProperties.class).sandboxPort()).isEqualTo(9090);
            assertThat(context.getBean(ModelCredentialRotationProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void credentialRotationRequiresExplicitConfigurationOptIn() {
        runner.withPropertyValues("identity.fingerprint-key=" + "01".repeat(32), "model.credential-rotation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModelCredentialRotationProperties.class).enabled()).isTrue();
        });
    }

    @Test
    void malformedSecretFailsWithoutLoggingItsValue(CapturedOutput output) {
        String invalidFixture = "invalid-secret-must-not-appear-in-logs";
        runner.withPropertyValues("identity.fingerprint-key=" + invalidFixture).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
        assertThat(output.getAll()).doesNotContain(invalidFixture);
    }

    @Test
    void malformedWrappingKeyConfigurationDoesNotLogItsValue(CapturedOutput output) {
        String invalidFixture = "fixture-wrapping-secret-must-not-appear";
        runner.withPropertyValues("identity.fingerprint-key=" + "01".repeat(32), "model.security.active-key-id=fixture", "model.security.keys=fixture:" + invalidFixture)
            .run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain(invalidFixture);
    }

    @Test
    void missingKeyAndInvalidPortFailClosed() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("identity.fingerprint-key=" + "01".repeat(32), "tracer.sandbox-port=0")
            .run(context -> assertThat(context).hasFailed());
    }
}
