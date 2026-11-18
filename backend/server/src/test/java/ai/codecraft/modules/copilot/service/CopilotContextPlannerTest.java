package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codecraft.modules.copilot.config.CopilotContextProperties;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotContextInputDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CopilotContextPlannerTest {
    private final CopilotContextPlanner planner = new CopilotContextPlanner(new CopilotRequestFactory(),
        new CopilotContextProperties(80, 1, 8192, 4096, 8192, 512, 2048), new ObjectMapper(),
        new CopilotProperties(2, 262144, 1048576, 16, 3, Duration.ofSeconds(130)));
    private final ToolExecutionRequest call = ToolExecutionRequest.builder().id("read-1").name("view_file").arguments("{\"path\":\"src/Main.java\"}").build();

    @Test void microCompactionFoldsOnlyOldCompleteUnitsBeforeTheAutomaticThreshold() {
        compareMicro();
    }

    java.util.Map<String, Long> compareMicro() {
        var recentCall = ToolExecutionRequest.builder().id("recent-read").name("read").arguments("{}").build();
        var rows = List.of(row(1, UserMessage.from("goal")), row(2, AiMessage.builder().thinking("keep-old-thinking").toolExecutionRequests(List.of(call)).build()),
            row(3, ToolExecutionResultMessage.from(call, "old-content".repeat(100))),
            row(4, AiMessage.builder().thinking("keep-recent-thinking").toolExecutionRequests(List.of(recentCall)).build()),
            row(5, ToolExecutionResultMessage.from(recentCall, "recent-content".repeat(100))));
        var plan = planner.plan(target(100000, ModelThinkingMode.OFF), input(rows, false));
        var messages = plan.projection().request().messages();
        var old = (ToolExecutionResultMessage) messages.get(3);
        assertThat(CopilotContextPlanner.bytes(ChatMessageSerializer.messageToJson(old))).isLessThanOrEqualTo(512);
        assertThat(old.text()).contains(rows.get(2).id(), "read_evidence");
        assertThat(old.id()).isEqualTo(call.id());
        assertThat((ToolExecutionResultMessage) messages.getLast()).isEqualTo(ToolExecutionResultMessage.from(recentCall, "recent-content".repeat(100)));
        assertThat(((AiMessage) messages.get(2)).thinking()).isEqualTo("keep-old-thinking");
        assertThat(((AiMessage) messages.get(4)).thinking()).isEqualTo("keep-recent-thinking");
        assertThat(rows.get(2).payload()).contains("old-content".repeat(100));
        assertThat(plan.safe()).isTrue();
        assertThat(plan.automaticNeeded()).isFalse();
        var keepAll = new CopilotContextPlanner(new CopilotRequestFactory(),
            new CopilotContextProperties(80, 8, 8192, 4096, 8192, 512, 2048), new ObjectMapper(),
            new CopilotProperties(2, 262144, 1048576, 16, 3, Duration.ofSeconds(130)));
        long before = keepAll.plan(target(100000, ModelThinkingMode.OFF), input(rows, false)).projection().encodedBytes();
        long after = plan.projection().encodedBytes();
        assertThat(before - after).isPositive().isEqualTo(plan.projection().microSavedBytes());
        return java.util.Map.of("beforeBytes", before, "afterBytes", after, "savedBytes", before - after);
    }

    @Test void microCompactionPreservesLongProviderCallIdsEvenWhenThePointerExceeds512Bytes() {
        var longCall = ToolExecutionRequest.builder().id("x".repeat(256)).name("read_source").arguments("{}").build();
        var rows = List.of(row(1, AiMessage.from(List.of(longCall))), row(2, ToolExecutionResultMessage.from(longCall, "large source".repeat(300))),
            row(3, AiMessage.from("recent")));
        var messages = planner.plan(target(100000, ModelThinkingMode.OFF), input(rows, false)).projection().request().messages();
        var result = (ToolExecutionResultMessage) messages.get(2);
        assertThat(result.id()).isEqualTo(longCall.id());
        assertThat(result.text()).contains(rows.get(1).id(), "read_evidence");
        assertThat(CopilotContextPlanner.bytes(ChatMessageSerializer.messageToJson(result))).isLessThan(1024);
    }

    @Test void microCompactionDoesNotFoldRecentUnitsOrEffectfulResults() {
        var write = ToolExecutionRequest.builder().id("write").name("write").arguments("{}").build();
        var rows = List.of(row(1, AiMessage.from(List.of(write))), row(2, ToolExecutionResultMessage.from(write, "confirmed".repeat(100))),
            row(3, AiMessage.from(List.of(call))), row(4, ToolExecutionResultMessage.from(call, "recent".repeat(200))));
        var messages = planner.plan(target(100000, ModelThinkingMode.OFF), input(rows, false)).projection().request().messages();
        assertThat(((ToolExecutionResultMessage) messages.get(2)).text()).isEqualTo("confirmed".repeat(100));
        assertThat(((ToolExecutionResultMessage) messages.getLast()).text()).isEqualTo("recent".repeat(200));
    }

    @Test void longWebEvidenceUsesOriginalMessageIdentityForRetainedPaging() {
        var request = ToolExecutionRequest.builder().id("web").name("web_read").arguments("{\"url\":\"https://example.com\"}").build();
        var rows = List.of(row(1, UserMessage.from("read docs")), row(2, AiMessage.from(List.of(request))),
            row(3, ToolExecutionResultMessage.from(request, "retained body ".repeat(3000))));
        String original = rows.getLast().payload();
        var plan = planner.plan(target(100000, ModelThinkingMode.OFF), input(rows, false));
        var output = (ToolExecutionResultMessage) plan.projection().request().messages().getLast();
        assertThat(output.text()).contains("CONTEXT_EVIDENCE_TRUNCATED", "read_evidence", rows.getLast().id());
        assertThat(rows.getLast().payload()).isEqualTo(original);
    }

    @Test void projectGuidancePrecedesSkillsAndTaskWithoutBecomingSystemAuthority() {
        var source = new CopilotContextInputDTO(null, null, 0, List.of(row(1, UserMessage.from("task"))), List.of(), List.of(), false, 0,
            List.of(ChatMessageSerializer.messageToJson(UserMessage.from("skill"))),
            List.of(ChatMessageSerializer.messageToJson(UserMessage.from("project rules"))));
        var plan = planner.plan(target(100000, ModelThinkingMode.OFF), source);
        assertThat(plan.safe()).isTrue();
        assertThat(plan.projection().request().messages().subList(1, 4)).containsExactly(
            UserMessage.from("project rules"), UserMessage.from("skill"), UserMessage.from("task"));
    }

    @Test void projectsRereadableTextWithoutChangingStoredPayloadThinkingOrCallIdentity() {
        var rows = history(); String original = rows.get(2).payload();
        var plan = planner.plan(target(100000, ModelThinkingMode.OFF), input(rows, false));
        assertThat(plan.safe()).isTrue();
        assertThat(rows.get(2).payload()).isEqualTo(original);
        var assistant = (AiMessage) plan.projection().request().messages().get(2);
        assertThat(assistant.thinking()).isEqualTo("opaque-thinking-preserved");
        assertThat(assistant.toolExecutionRequests()).containsExactly(call);
        var result = (ToolExecutionResultMessage) plan.projection().request().messages().get(3);
        assertThat(result.id()).isEqualTo(call.id()); assertThat(result.toolName()).isEqualTo("view_file");
        assertThat(result.text()).contains("CONTEXT_EVIDENCE_TRUNCATED", rows.get(2).id(), "read_evidence");
        assertThat(ChatMessageSerializer.messageToJson(result).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);
    }

    @Test void keepsNativeOpaqueAttributesInRetainedContextButNotInSummaryInstructions() {
        var attributes = Map.<String, Object>of("thinking_signature", "signed-fixture", "redacted_thinking", List.of("redacted-fixture"), "encrypted_reasoning", "encrypted-fixture");
        var rows = new ArrayList<>(history());
        rows.set(1, row(2, AiMessage.builder().thinking("opaque-thinking-preserved").attributes(attributes).toolExecutionRequests(List.of(call)).build()));
        var plan = planner.plan(target(100000, ModelThinkingMode.ON), input(rows, false));
        var assistant = (AiMessage) plan.projection().request().messages().get(2);
        assertThat(assistant.attributes()).containsAllEntriesOf(attributes);
        assertThat(plan.compaction()).isNotNull();
        assertThat(ChatMessageSerializer.messagesToJson(plan.compaction().request().messages()))
            .doesNotContain("signed-fixture", "redacted-fixture", "encrypted-fixture", "opaque-thinking-preserved");
    }

    @Test void selectsOnlyCompleteOldUnitsReservesOutputAndKeepsRecentOriginal() {
        var plan = planner.plan(target(100000, ModelThinkingMode.OFF), input(history(), false));
        assertThat(plan.compaction()).isNotNull();
        assertThat(plan.compaction().throughSequence()).isEqualTo(3);
        assertThat(plan.compaction().sourceIds()).hasSize(3);
        assertThat(plan.compaction().outputTokens()).isEqualTo(1024);
        assertThat(plan.compaction().estimatedTokens() + plan.compaction().outputTokens()).isLessThan(100000);
        assertThat((long) plan.compaction().maximumSummaryBytes()).isLessThan(plan.compaction().replacedBytes());
        assertThat(plan.compaction().request().parameters().toolSpecifications()).isEmpty();
    }

    @Test void refusesIncompleteOrWronglyPairedCanonicalHistoryAndDoesNotSummarizeAnOpenBatch() {
        var rows = history(); var open = rows.subList(0, 2);
        assertThatThrownBy(() -> planner.plan(target(100000, ModelThinkingMode.OFF), input(open, false))).isInstanceOf(IllegalStateException.class);
        var page = planner.plan(target(100000, ModelThinkingMode.OFF), input(open, true));
        assertThat(page.safe()).isFalse(); assertThat(page.automaticNeeded()).isTrue(); assertThat(page.compaction()).isNull();
        var wrong = List.of(rows.get(0), rows.get(1), row(3, ToolExecutionResultMessage.from("wrong", "view_file", "OK")));
        assertThatThrownBy(() -> planner.plan(target(100000, ModelThinkingMode.OFF), input(wrong, false))).isInstanceOf(IllegalStateException.class);
    }

    @Test void keepsPinnedGoalAndPendingDirectionsAfterNonAuthoritativeSummaryWithoutRepeatingOldNativeCalls() {
        var rows = history();
        var source = new CopilotContextInputDTO(UUID.randomUUID().toString(), "{\"goal\":\"historical evidence only\"}", 3,
            List.of(rows.get(3)), List.of(rows.get(0)), List.of(ChatMessageSerializer.messageToJson(UserMessage.from("new direction"))), false, 0);
        var messages = planner.plan(target(100000, ModelThinkingMode.OFF), source).projection().request().messages();
        assertThat(messages).hasSize(5);
        assertThat(((UserMessage) messages.get(1)).singleText()).contains("non-authoritative", "historical evidence only");
        assertThat(((UserMessage) messages.get(2)).singleText()).isEqualTo("Preserve the user's explicit choice");
        assertThat(((UserMessage) messages.getLast()).singleText()).isEqualTo("new direction");
        assertThat(messages.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast).noneMatch(AiMessage::hasToolExecutionRequests)).isTrue();
    }

    @Test void checksThresholdSeparatelyFromHardWindowAndNeverLabelsEstimateAsActual() {
        var source = input(List.of(row(1, UserMessage.from("goal ".repeat(2000))), row(2, AiMessage.from("recent"))), false);
        long estimated = planner.plan(target(100000, ModelThinkingMode.OFF), source).projection().estimatedTokens();
        var near = planner.plan(target(Math.toIntExact(estimated + 1025), ModelThinkingMode.OFF), source);
        assertThat(near.safe()).isTrue(); assertThat(near.automaticNeeded()).isTrue();
        assertThat(planner.plan(target(Math.toIntExact(estimated + 1024), ModelThinkingMode.OFF), source).safe()).isFalse();
        assertThat(planner.plan(target(100000, ModelThinkingMode.OFF), input(List.of(row(1, UserMessage.from("short"))), false)).compaction()).isNull();
    }

    @Test void refusesOversizeIrreducibleGoalAndDoesNotLowerThinkingOutputBudget() {
        var goal = row(1, UserMessage.from("x".repeat(20000)));
        var plan = planner.plan(target(8192, ModelThinkingMode.OFF), input(List.of(goal), false));
        assertThat(plan.safe()).isFalse(); assertThat(plan.compaction()).isNull();
        var configuration = new ModelConfigurationResponse("model", "connection", "fixture", "fixture", 100000, 8192,
            ModelThinkingMode.ON, null, 4096, null, "1", "1", "USER_PROVIDED", false);
        assertThat(planner.plan(new ModelInvocationDTO(null, configuration, "fixture"), input(history(), false)).compaction().outputTokens()).isEqualTo(8192);
    }

    @Test void doesNotChargeToSummarizeAGoalThatMustBePinnedAgain() {
        var goal = row(1, UserMessage.from("explicit user constraint ".repeat(300)));
        var source = new CopilotContextInputDTO(null, null, 0, List.of(goal, row(2, AiMessage.from("recent"))),
            List.of(goal), List.of(), false, 0);
        assertThat(planner.plan(target(100000, ModelThinkingMode.OFF), source).compaction()).isNull();
    }

    @Test void shrinksRereadableResultsBeforeChargingToSummarizeAnOtherwiseOversizeWholeBatch() {
        var calls = new ArrayList<ToolExecutionRequest>();
        for (int index = 0; index < 16; index++) calls.add(ToolExecutionRequest.builder().id("r-" + index).name("view_file").arguments("{}").build());
        var rows = new ArrayList<CopilotMessageEntity>(); rows.add(row(1, UserMessage.from("goal")));
        rows.add(row(2, AiMessage.from(calls)));
        for (int index = 0; index < 16; index++) rows.add(row(index + 3, ToolExecutionResultMessage.from(calls.get(index), "x".repeat(6000))));
        rows.add(row(19, AiMessage.from("recent")));
        assertThat(planner.plan(target(32768, ModelThinkingMode.OFF), input(rows, false)).safe()).isTrue();
    }

    @Test void doesNotChargeForACompressionThatCannotFitIrreducibleSkillGuidance() {
        var goal = row(1, UserMessage.from("current goal"));
        var source = new CopilotContextInputDTO(null, null, 0,
            List.of(goal, row(2, AiMessage.from("x".repeat(16000))), row(3, AiMessage.from("recent"))), List.of(goal), List.of(), false, 0,
            List.of(ChatMessageSerializer.messageToJson(UserMessage.from("s".repeat(64000)))));
        var plan = planner.plan(target(32768, ModelThinkingMode.OFF), source);
        assertThat(plan.safe()).isFalse();
        assertThat(plan.compaction()).isNull();
    }

    @Test void doesNotChargeWhenUnappliedDirectionsAloneCannotFitTheWindow() {
        var pending = new ArrayList<String>();
        for (int index = 0; index < 32; index++) pending.add(ChatMessageSerializer.messageToJson(UserMessage.from("x".repeat(4000))));
        var source = new CopilotContextInputDTO(null, null, 0, List.of(row(1, UserMessage.from("old goal")),
            row(2, AiMessage.from("old explanation ".repeat(1000))), row(3, AiMessage.from("recent"))), List.of(), pending, false, 0);
        var plan = planner.plan(target(100000, ModelThinkingMode.OFF), source);
        assertThat(plan.safe()).isFalse(); assertThat(plan.compaction()).isNull();
    }

    @Test void expandsTheCompletePrefixWhenKeepingRecentOpaqueHistoryWouldPreventContinuation() {
        var goal = row(1, UserMessage.from("current goal stays exact"));
        var completed = row(2, AiMessage.builder().text("confirmed ordinary visible evidence").thinking("x".repeat(20000)).build());
        var source = new CopilotContextInputDTO(null, null, 0, List.of(goal, completed), List.of(goal), List.of(), false, 0);
        var plan = planner.plan(target(16000, ModelThinkingMode.OFF), source);
        assertThat(plan.safe()).isFalse(); assertThat(plan.compaction()).isNotNull();
        assertThat(plan.compaction().throughSequence()).isEqualTo(2);
        assertThat(plan.compaction().request().messages().toString()).doesNotContain("x".repeat(20000));
        var foreign = new CopilotContextInputDTO(null, null, 0, List.of(goal, completed, row(3, AiMessage.from("recent"))),
            List.of(goal), List.of(), false, 3);
        assertThat(planner.plan(target(100000, ModelThinkingMode.OFF), foreign).compaction()).isNull();
    }

    @Test void canRecompactAPreviousSummaryWithoutConsumingTheNewPinnedGoal() {
        String previous = "{\"goal\":\"" + "g".repeat(1600) + "\",\"state\":\"" + "s".repeat(1600)
            + "\",\"decisions\":[\"" + "d".repeat(1600) + "\"],\"changes\":[\"" + "c".repeat(1600) + "\"]}";
        var goal = row(11, UserMessage.from("new goal stays verbatim"));
        var source = new CopilotContextInputDTO(UUID.randomUUID().toString(), previous, 10, List.of(goal), List.of(goal), List.of(), false, 0);
        var plan = planner.plan(target(100000, ModelThinkingMode.OFF), source);
        assertThat(plan.compaction()).isNotNull(); assertThat(plan.compaction().throughSequence()).isEqualTo(10);
        assertThat(plan.compaction().sourceIds()).isEmpty();
        assertThat(plan.compaction().request().messages().toString()).doesNotContain("new goal stays verbatim");
    }

    @Test void convertsOnlyTheForeignPrefixAndPreservesCurrentModelToolPairing() {
        var rows = List.of(row(1, UserMessage.from("old goal")), row(2, AiMessage.builder().text("old reply").thinking("foreign-signature").build()),
            row(3, UserMessage.from("read once then answer")), row(4, AiMessage.builder().thinking("current-signature").toolExecutionRequests(List.of(call)).build()),
            row(5, ToolExecutionResultMessage.from(call, "confirmed current result")));
        var source = new CopilotContextInputDTO(null, null, 0, rows, List.of(rows.get(2)), List.of(), false, 2);
        var messages = planner.plan(target(100000, ModelThinkingMode.OFF), source).projection().request().messages();
        assertThat(messages.getLast()).isInstanceOf(ToolExecutionResultMessage.class);
        assertThat(messages.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast).toList())
            .singleElement().satisfies(value -> assertThat(value.thinking()).isEqualTo("current-signature"));
        assertThat(ChatMessageSerializer.messagesToJson(messages)).doesNotContain("foreign-signature");
    }

    @Test void rejectsAResultWithTheRightIdButWrongToolName() {
        var rows = history();
        var wrong = List.of(rows.get(0), rows.get(1), row(3, ToolExecutionResultMessage.from(call.id(), "run_tests", "OK")));
        assertThatThrownBy(() -> planner.plan(target(100000, ModelThinkingMode.OFF), input(wrong, false))).isInstanceOf(IllegalStateException.class);
    }

    private List<CopilotMessageEntity> history() {
        return List.of(row(1, UserMessage.from("Preserve the user's explicit choice")),
            row(2, AiMessage.builder().thinking("opaque-thinking-preserved").toolExecutionRequests(List.of(call)).build()),
            row(3, ToolExecutionResultMessage.from(call, "{\"code\":\"OK\",\"text\":\"" + "source 😀 中文 ".repeat(3000) + "\"}")),
            row(4, AiMessage.from("Recent answer remains verbatim")));
    }
    private CopilotContextInputDTO input(List<CopilotMessageEntity> rows, boolean more) {
        return new CopilotContextInputDTO(null, null, 0, rows, List.of(), List.of(), more, 0);
    }
    private CopilotMessageEntity row(long sequence, ChatMessage message) {
        return new CopilotMessageEntity(sequence, UUID.randomUUID().toString(), "task", "exercise",
            message instanceof UserMessage ? "USER" : message instanceof AiMessage ? "ASSISTANT" : "TOOL", ChatMessageSerializer.messageToJson(message), Instant.EPOCH);
    }
    private ModelInvocationDTO target(int window, ModelThinkingMode thinking) {
        return new ModelInvocationDTO(null, new ModelConfigurationResponse("model", "connection", "fixture", "fixture", window,
            1024, thinking, null, null, null, "1", "1", "USER_PROVIDED", false), "fixture");
    }
}
