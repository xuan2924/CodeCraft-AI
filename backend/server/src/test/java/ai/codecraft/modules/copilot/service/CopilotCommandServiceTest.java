package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.repository.CopilotCommandRepository;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.service.CommandTaskCoordinator;
import ai.codecraft.modules.workspace.service.CommandTaskStore;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CopilotCommandServiceTest {
    private final CommandTaskStore children = mock(CommandTaskStore.class);
    private final CopilotCommandService service = new CopilotCommandService(mock(CopilotCommandStore.class), mock(CopilotCommandRepository.class), children,
        mock(CommandTaskCoordinator.class), mock(StorageCapacityService.class), new CopilotCommandResultFormatter(), new ObjectMapper());
    @Test void strictToolArgumentsReuseTheWorkspaceCommandContract() {
        var input = new CommandStartRequest("printf 'hello'", "src", "1");
        assertThat(service.parse("{\"expected_revision\":\"1\",\"command\":\"printf 'hello'\",\"directory\":\"src\"}")).isEqualTo(input);
        verify(children).validate(input);
    }
    @Test void malformedOrAmbiguousArgumentsNeverBecomeACommand() {
        for (String input : new String[]{"null", "[]", "{}", "{\"expected_revision\":1,\"command\":\"echo hi\",\"directory\":\".\"}",
            "{\"expected_revision\":\"1\",\"command\":\"echo hi\",\"directory\":\".\",\"command\":\"rm x\"}",
            "{\"expected_revision\":\"1\",\"command\":\"echo hi\",\"directory\":\".\",\"auto\":true}",
            "{\"expected_revision\":\"1\",\"command\":\"echo hi\",\"directory\":\".\"} {}", "x".repeat(32769)}) {
            assertThatThrownBy(() -> service.parse(input)).isInstanceOf(BusinessException.class);
        }
    }
}
