package ai.codecraft.modules.modelaccess.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModelCredentialCipherTest {
    private static final String FIRST = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SECOND = Base64.getEncoder().encodeToString("1".repeat(32).getBytes(StandardCharsets.UTF_8));

    @Test
    void authenticatedEncryptionBindsOwnerAndConnectionWithoutLeakingPlaintext() {
        var settings = new ModelSecurityProperties("first", "first:" + FIRST);
        var cipher = new ModelCredentialCipher(settings);
        var sealed = cipher.encrypt("owner", "connection", "test-model-credential");
        assertEquals("test-model-credential", cipher.decrypt("owner", "connection", sealed));
        assertNotEquals(sealed, cipher.encrypt("owner", "connection", "test-model-credential"));
        assertFalse(sealed.toString().contains("test-model-credential"));
        assertFalse(settings.toString().contains(FIRST));
        assertThrows(BusinessException.class, () -> cipher.decrypt("another-owner", "connection", sealed));
        assertThrows(BusinessException.class, () -> cipher.decrypt("owner", "another-connection", sealed));
    }

    @Test
    void deploymentRotationReadsOldCiphertextButWritesOnlyTheActiveKey() {
        var old = new ModelCredentialCipher(new ModelSecurityProperties("first", "first:" + FIRST));
        var sealed = old.encrypt("owner", "connection", "fixture-key");
        var rotating = new ModelCredentialCipher(new ModelSecurityProperties("second", "first:" + FIRST + ",second:" + SECOND));
        var replaced = rotating.rotate("owner", "connection", sealed);
        assertEquals("second", replaced.keyId());
        assertEquals("fixture-key", rotating.decrypt("owner", "connection", replaced));
        var afterRemoval = new ModelCredentialCipher(new ModelSecurityProperties("second", "second:" + SECOND));
        assertEquals("fixture-key", afterRemoval.decrypt("owner", "connection", replaced));
        assertThrows(BusinessException.class, () -> afterRemoval.decrypt("owner", "connection", sealed));
    }

    @Test
    void missingDeploymentKeysNeverBecomeAnImplicitTestOrPlatformFallback() {
        var unavailable = new ModelCredentialCipher(new ModelSecurityProperties("", ""));
        assertEquals("MODEL_ENCRYPTION_UNAVAILABLE", assertThrows(BusinessException.class,
            () -> unavailable.encrypt("owner", "connection", "fixture")).code());
        assertThrows(IllegalArgumentException.class, () -> new ModelCredentialCipher(new ModelSecurityProperties("first", "first:invalid")));
        assertThrows(IllegalArgumentException.class, () -> new ModelCredentialCipher(new ModelSecurityProperties("first", "first:" + FIRST + ",first:" + FIRST)));
    }
}
