package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.copilot.gateway.CopilotWebGateway;
import ai.codecraft.modules.copilot.model.dto.CopilotWebFetchDTO;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.service.ExerciseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopilotWebToolsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CopilotWebGateway gateway = mock(CopilotWebGateway.class);
    private final ExerciseService exercises = mock(ExerciseService.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final CopilotWebTools tools = new CopilotWebTools(gateway, exercises, Clock.fixed(now, ZoneOffset.UTC), json);
    private void active() {
        when(exercises.find("session", "exercise")).thenReturn(new ExerciseResponse("exercise", "problem", "instance", "prepare", "ACTIVE", "7", "1",
            List.of(), now, now.plusSeconds(60), "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null));
    }
    @Test void distinguishesSearchSnippetsFromFetchedBodiesAndPreservesSourcesAndCredits() throws Exception {
        active();
        when(gateway.call(eq("search"), any(), any())).thenReturn(new CopilotWebFetchDTO("OK", json.readTree("{\"results\":[{\"url\":\"https://example.com/doc\",\"title\":\"Official\",\"content\":\"snippet\"}],\"usage\":{\"credits\":1}}")));
        var result = json.readTree(tools.execute("session", "exercise", "web_search", "{\"query\":\"java docs\"}"));
        assertThat(result.path("kind").textValue()).isEqualTo("SEARCH_SNIPPETS");
        assertThat(result.path("sources").get(0).path("url").textValue()).isEqualTo("https://example.com/doc");
        assertThat(result.path("serviceCredits").textValue()).isEqualTo("1");
        verify(gateway).call(eq("search"), eq(Map.of("query", "java docs", "search_depth", "basic", "max_results", 5, "include_answer", false, "include_raw_content", false, "include_usage", true)), any());
        var response = json.createObjectNode(); response.putArray("failed_results");
        response.putArray("results").addObject().put("url", "https://example.com/doc").put("raw_content", "大".repeat(20000));
        when(gateway.call(eq("extract"), any(), any())).thenReturn(new CopilotWebFetchDTO("OK", response));
        result = json.readTree(tools.execute("session", "exercise", "web_read", "{\"url\":\"https://example.com/doc\"}"));
        assertThat(result.path("kind").textValue()).isEqualTo("FETCHED_BODY");
        assertThat(result.path("sources").get(0).path("retainedTruncated").booleanValue()).isTrue();
        assertThat(CopilotContextPlanner.bytes(result.path("sources").get(0).path("content").textValue())).isLessThanOrEqualTo(32768);
        assertThat(result.path("usageUnknown").booleanValue()).isTrue();
    }
    @Test void failuresAndUnsafeUrlsNeverBecomeEmptySuccess() throws Exception {
        active();
        for (String input : List.of("{\"url\":\"file:///private\"}", "{\"url\":\"https://user:password@example.com/\"}", "{\"url\":\"https://example.com\",\"url\":\"https://other.com\"}", "null")) {
            assertThat(json.readTree(tools.execute("session", "exercise", "web_read", input)).path("code").textValue()).isEqualTo("INVALID_ARGUMENTS");
        }
        when(gateway.call(eq("extract"), any(), any())).thenReturn(new CopilotWebFetchDTO("OK", json.readTree("{\"results\":[],\"failed_results\":[{\"url\":\"https://example.com\",\"error\":\"blocked\"}],\"usage\":{\"credits\":0}}")));
        var failed = json.readTree(tools.execute("session", "exercise", "web_read", "{\"url\":\"https://example.com\"}"));
        assertThat(failed.path("code").textValue()).isEqualTo("WEB_FETCH_FAILED");
        assertThat(failed.path("notice").textValue()).contains("blocked");
        assertThat(failed.path("serviceCredits").textValue()).isEqualTo("0");
        assertThat(failed.path("usageUnknown").booleanValue()).isFalse();
        when(gateway.call(eq("search"), any(), any())).thenReturn(new CopilotWebFetchDTO("WEB_NOT_CONFIGURED", null));
        assertThat(json.readTree(tools.execute("session", "exercise", "web_search", "{\"query\":\"docs\"}")).path("code").textValue()).isEqualTo("WEB_NOT_CONFIGURED");
    }
}
