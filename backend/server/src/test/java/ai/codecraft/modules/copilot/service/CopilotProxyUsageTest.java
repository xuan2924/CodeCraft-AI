package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.repository.CopilotUsageRepository;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.dto.ModelCostSubtotalResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse;
import ai.codecraft.modules.modelaccess.service.ProxyAuditService;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopilotProxyUsageTest {
    @Test void cliAndNativeCallsShareCompatibleTotalsWithoutLosingUnknownsOrPrecision() {
        var nativeUsage = mock(CopilotUsageRepository.class);
        var proxy = mock(ProxyAuditService.class);
        var identity = mock(IdentityTransactionService.class);
        when(identity.authorizePrivateDelivery("session")).thenReturn(new SessionAuthorizationDTO("user", "USER", false, 1, Instant.MAX));
        when(nativeUsage.totals("exercise")).thenReturn(List.of(new ModelUsageTotalsResponse("CHAT", "1", "1", "9007199254740993", null, "2", null, "3", null, "1")));
        when(proxy.totals("exercise")).thenReturn(List.of(new ModelUsageTotalsResponse("CLI_PROXY", "2", "1", "10", null, "2", "3", "4", "14", "1")));
        when(nativeUsage.costs("exercise")).thenReturn(List.of(new ModelCostSubtotalResponse("CHAT", "USD", "0.000001", "1")));
        when(proxy.costs("exercise")).thenReturn(List.of(new ModelCostSubtotalResponse("CLI_PROXY", "USD", "0.000002", "1")));
        var service = new CopilotUsageService(nativeUsage, mock(CopilotCompactionRepository.class), identity,
            mock(ExerciseTransactionService.class), mock(CopilotConversationStore.class), proxy);
        var result = service.currentInConversation("session", "exercise", "conversation");
        assertThat(result.totals()).hasSize(1);
        assertThat(result.totals().getFirst().purpose()).isEqualTo("CHAT");
        assertThat(result.totals().getFirst().calls()).isEqualTo("3");
        assertThat(result.totals().getFirst().inputTokens()).isEqualTo("9007199254741003");
        assertThat(result.totals().getFirst().ordinaryInputTokens()).isNull();
        assertThat(result.costs()).containsExactly(new ModelCostSubtotalResponse("CHAT", "USD", "0.000003", "2"));
    }
}
