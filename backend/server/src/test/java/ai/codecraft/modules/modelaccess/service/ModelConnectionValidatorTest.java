package ai.codecraft.modules.modelaccess.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.codecraft.common.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelConnectionValidatorTest {
    private final ModelConnectionValidator validator = new ModelConnectionValidator();

    @ParameterizedTest
    @ValueSource(strings = {"https://api.deepseek.com", "https://localhost:8443/api", "https://127.0.0.1/v1", "https://[::1]/v1", "https://model.internal/api?version=1"})
    void httpsTargetClassificationDoesNotBecomeAnUnapprovedNetworkOrDnsPolicy(String value) {
        assertEquals(value, validator.baseUri(value).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/api", "file:///tmp/key", "https:///missing-host", "https://user:password@example.test", "https://example.test/#fragment", "https://example.test:99999", "not a url"})
    void malformedNonHttpsAndCredentialBearingAddressesAreRejectedWithoutEchoingInput(String value) {
        assertEquals("MODEL_INVALID_REQUEST", assertThrows(BusinessException.class, () -> validator.baseUri(value)).code());
    }
}
