package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CopilotTestToolsTest {
    private final CopilotTestTools tools = new CopilotTestTools(mock(CopilotTestStore.class), mock(StorageCapacityService.class),
        new CopilotTestResultFormatter(), new ObjectMapper());

    @Test
    void acceptsOnlyAnExactSavedRevisionAndPublishedTarget() {
        assertThat(tools.parse("{\"expected_revision\":\"9223372036854775807\",\"target\":\"base\"}").expectedRevision()).isEqualTo("9223372036854775807");
    }

    @ParameterizedTest
    @ValueSource(strings = { "null", "[]", "{}", "{\"expected_revision\":1,\"target\":\"base\"}",
        "{\"expected_revision\":\"01\",\"target\":\"base\"}", "{\"expected_revision\":\"-1\",\"target\":\"base\"}",
        "{\"expected_revision\":\"9223372036854775808\",\"target\":\"base\"}",
        "{\"expected_revision\":\"1\",\"target\":\"sh -c anything\"}",
        "{\"expected_revision\":\"1\",\"target\":\"base\",\"command\":\"mvn test\"}",
        "{\"expected_revision\":\"1\",\"target\":\"base\",\"target\":\"base\"}",
        "{\"expected_revision\":\"1\",\"target\":\"base\"} {}" })
    void rejectsCommandsCoercionAmbiguityAndUnknownFields(String arguments) {
        assertThatThrownBy(() -> tools.parse(arguments)).isInstanceOf(BusinessException.class).hasMessage("COPILOT_INVALID_REQUEST");
    }

    @Test
    void rejectsOversizedOrMissingInputBeforeAdmission() {
        assertThatThrownBy(() -> tools.parse(" ".repeat(4097))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tools.parse(null)).isInstanceOf(BusinessException.class);
    }
}
