package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ai.codecraft.modules.modelaccess.security.ProxyTokens;
import ai.codecraft.modules.workspace.service.TerminalModelAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProxyUsageTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void anthropicCumulativeDeltasAndCacheAreDisjoint() throws Exception {
        var usage = new ProxyUsage(true);
        usage.accept(json.readTree("{\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":1,\"cache_read_input_tokens\":2,\"cache_creation_input_tokens\":3}}}"));
        usage.accept(json.readTree("{\"usage\":{\"output_tokens\":4}}"));
        usage.accept(json.readTree("{\"usage\":{\"output_tokens\":4}}"));
        assertThat(usage.facts().inputTokens()).isEqualTo(15);
        assertThat(usage.facts().ordinaryInputTokens()).isEqualTo(10);
        assertThat(usage.facts().cacheWriteTokens()).isEqualTo(3);
        assertThat(usage.facts().outputTokens()).isEqualTo(4);
        assertThat(usage.facts().complete()).isTrue();
    }
    @Test void responsesReasoningAndMissingUsageStayHonest() throws Exception {
        var usage = new ProxyUsage(false);
        usage.accept(json.readTree("{}"));
        assertThat(usage.facts().inputTokens()).isNull();
        assertThat(usage.facts().complete()).isFalse();
        usage.accept(json.readTree("{\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":2},\"output_tokens_details\":{\"reasoning_tokens\":3}}}}"));
        assertThat(usage.facts().ordinaryInputTokens()).isEqualTo(8);
        assertThat(usage.reasoning()).isEqualTo(3);
        assertThat(usage.facts().totalTokens()).isEqualTo(15);
    }
    @Test void impossibleCacheDoesNotProduceAFalseBill() throws Exception {
        var usage = new ProxyUsage(false);
        usage.accept(json.readTree("{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":8}}}"));
        assertThat(usage.facts().reason()).isEqualTo("USAGE_INCONSISTENT");
        assertThat(usage.facts().complete()).isFalse();
    }
    @Test void signedGrantExpiresAndIsUserBound() {
        var clock = mock(Clock.class); var authority = mock(TerminalModelAccessService.class);
        String id = "10000000-0000-0000-0000-000000000001";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(authority.authorize(id, id, 3)).thenReturn(new TerminalModelAccessService.Authority(id, id, id, 3));
        var tokens = new ProxyTokens(clock, authority);
        String token = tokens.issue(id, id, 3);
        assertThat(tokens.verify(token).epoch()).isEqualTo(3);
        when(clock.instant()).thenReturn(now.plusSeconds(900));
        assertThatThrownBy(() -> tokens.verify(token)).hasMessage("Proxy forbidden");
        when(clock.instant()).thenReturn(now);
        when(authority.authorize(id, id, 3)).thenReturn(new TerminalModelAccessService.Authority(id, id, "different-user", 3));
        assertThatThrownBy(() -> tokens.verify(token)).hasMessage("Proxy forbidden");
    }
}
