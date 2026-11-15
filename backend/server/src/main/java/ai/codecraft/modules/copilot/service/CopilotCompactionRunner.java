package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionPlanDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionReceiptDTO;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Runs on the existing bounded Copilot pool, never on an observation or HTTP thread. */
@Service
public class CopilotCompactionRunner {
    private final CopilotCompactionExecution execution;
    private final CopilotContextPreparation preparation;
    private final CopilotContextPlanner planner;
    private final CopilotSummaryCodec codec;
    private final CopilotCallEvidence evidence;
    private final CopilotRequestFactory requests;
    private final StorageCapacityService capacity;
    private final ModelCallDriver driver;
    private final CopilotProperties limits;
    private final ModelCallProperties calls;
    private final ObjectMapper json;

    public CopilotCompactionRunner(CopilotCompactionExecution execution, CopilotContextPreparation preparation,
        CopilotContextPlanner planner, CopilotSummaryCodec codec, CopilotCallEvidence evidence, CopilotRequestFactory requests,
        StorageCapacityService capacity, ModelCallDriver driver, CopilotProperties limits, ModelCallProperties calls,
        @Qualifier("workspaceJson") ObjectMapper json) {
        this.execution = execution; this.preparation = preparation; this.planner = planner; this.codec = codec; this.evidence = evidence;
        this.requests = requests; this.capacity = capacity; this.driver = driver; this.limits = limits; this.calls = calls; this.json = json;
    }

    public void runStandalone(String id) {
        try { run(id, UUID.randomUUID().toString(), null); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    public void run(String id, String worker, String inlineTask) throws InterruptedException {
        if (execution.claim(id, worker, inlineTask) == null) return;
        boolean repair = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var ticket = execution.ticket(id, worker);
                if (ticket == null) return;
                var prepared = preparation.prepare(ticket);
                if (prepared == null) continue;
                var plan = prepared.plan().compaction();
                if (plan == null) { execution.noCandidate(id, worker, prepared.plan().safe()); return; }
                var candidate = repair ? repair(plan) : plan;
                if (candidate.estimatedTokens() + candidate.outputTokens() >= Math.min(ticket.target().model().contextWindow(), limits.contextBytes())) {
                    execution.fail(id, worker, "COPILOT_CONTEXT_REQUIRED"); return;
                }
                String ids = json.writeValueAsString(candidate.sourceIds());
                var dispatch = capacity.admit(ticket.sessionHash(), ticket.exerciseId(),
                    measurement -> execution.begin(id, worker, prepared, candidate, ids, measurement));
                if (dispatch == null) continue;
                var result = driver.call(dispatch.target(), dispatch.request(),
                    new ModelInvocationLimitsDTO(candidate.outputTokens(), dispatch.target().model().thinkingBudgetTokens()),
                    Math.min(limits.responseBytes(), calls.maxDecodedBytes()), dispatch.call().notAfter(), new PartialCapture(id, worker, dispatch.call().id()));
                var receipt = receipt(id, candidate, ticket.summaryId(), result.response());
                if (!execution.complete(id, worker, dispatch.call().id(), receipt)) return;
                repair = true;
            }
            throw new InterruptedException("Summary worker interrupted");
        } catch (InterruptedException interrupted) {
            Thread.interrupted();
            try { execution.fail(id, worker, "MODEL_RESULT_UNKNOWN"); }
            catch (RuntimeException cleanup) { interrupted.addSuppressed(cleanup); }
            finally { Thread.currentThread().interrupt(); }
            throw interrupted;
        } catch (BusinessException rejected) {
            try { execution.fail(id, worker, rejected.code()); }
            catch (RuntimeException cleanup) { rejected.addSuppressed(cleanup); throw rejected; }
        } catch (JsonProcessingException invalid) {
            throw new UncheckedIOException("Cannot encode summary evidence", invalid);
        }
    }

    private CopilotCompactionReceiptDTO receipt(String id, CopilotCompactionPlanDTO plan, String previousSummary, ChatResponse response) {
        AiMessage assistant = response.aiMessage();
        String raw = ChatMessageSerializer.messageToJson(assistant);
        String summary = null; String reason = null;
        try {
            summary = codec.prepare(assistant, plan.maximumSummaryBytes(), plan.sourceIds(), previousSummary);
            if (!planner.smaller(plan, id, summary)) throw new BusinessException(CopilotErrorCode.COPILOT_SUMMARY_INVALID);
        } catch (BusinessException invalid) {
            if (invalid.errorCode() != CopilotErrorCode.COPILOT_SUMMARY_INVALID) throw invalid;
            summary = null; reason = invalid.code();
        }
        TokenUsage usage = response.tokenUsage();
        Long input = usage == null ? null : count(usage.inputTokenCount());
        Long output = usage == null ? null : count(usage.outputTokenCount());
        Long total = usage == null ? null : count(usage.totalTokenCount());
        return new CopilotCompactionReceiptDTO(raw, summary, reason, input, output, total,
            input == null || output == null || total == null, evidence.usage(usage));
    }

    private Long count(Integer value) { return value == null || value < 0 ? null : value.longValue(); }

    private CopilotCompactionPlanDTO repair(CopilotCompactionPlanDTO plan) {
        int maximum = Math.max(512, plan.maximumSummaryBytes() / 2);
        List<ChatMessage> messages = new ArrayList<>(plan.request().messages());
        messages.add(UserMessage.from("The previous completed summary was invalid. Return only the required five-field JSON object, no tools or extra fields. "
            + "Use genuine supplied evidence IDs; shorten prose and omit code. Maximum JSON UTF-8 bytes: " + maximum));
        var request = ChatRequest.builder().messages(messages).toolSpecifications(List.of()).build();
        return new CopilotCompactionPlanDTO(plan.throughSequence(), plan.sourceIds(), request, requests.estimate(request),
            plan.replacedBytes(), plan.outputTokens(), maximum);
    }

    private final class PartialCapture implements Consumer<String> {
        private final String id;
        private final String worker;
        private final String call;
        private final StringBuilder text = new StringBuilder();
        private int persisted;
        private long lastFlush;
        PartialCapture(String id, String worker, String call) { this.id = id; this.worker = worker; this.call = call; }
        @Override public void accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            text.append(chunk);
            long now = System.nanoTime();
            if (lastFlush == 0 || text.length() - persisted >= 2048 || now - lastFlush >= 200_000_000L) {
                execution.partial(id, worker, call, ChatMessageSerializer.messageToJson(AiMessage.from(text.toString())));
                persisted = text.length(); lastFlush = now;
            }
        }
    }
}
