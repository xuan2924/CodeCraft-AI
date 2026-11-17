package ai.codecraft.modules.platformagent.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.config.ModelCredentialRotationProperties;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.service.ModelUsageAccounting;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PlatformCheckWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformCheckWorker.class);
    private final PlatformCheckStore store;
    private final PlatformAgentStore agents;
    private final ModelCallDriver driver;
    private final ModelUsageAccounting usage;
    private final PlatformDefinitions definitions;
    private final PlatformPrompts prompts;
    private final ThreadPoolExecutor workers;
    private final PlatformCredentialRotationStore rotation;
    private final ModelCredentialRotationProperties rotationPolicy;
    private String rotationCursor;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public PlatformCheckWorker(PlatformCheckStore store, PlatformAgentStore agents, ModelCallDriver driver, ModelUsageAccounting usage,
        PlatformDefinitions definitions, PlatformPrompts prompts, PlatformAgentProperties policy,
        PlatformCredentialRotationStore rotation, ModelCredentialRotationProperties rotationPolicy) {
        this.store = store; this.agents = agents; this.driver = driver; this.usage = usage; this.definitions = definitions; this.prompts = prompts;
        this.rotation = rotation; this.rotationPolicy = rotationPolicy;
        workers = new ThreadPoolExecutor(policy.workers(), policy.workers(), 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
            Thread.ofPlatform().name("platform-check-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelay = 1000)
    public void discover() {
        try {
            for (String id : store.discover()) {
                if (!active.add(id)) continue;
                try { workers.execute(() -> run(id)); }
                catch (RejectedExecutionException full) { active.remove(id); break; }
            }
        } catch (RuntimeException failure) { LOG.warn("Platform check discovery failed: {}", failure.getClass().getSimpleName()); }
    }

    @Scheduled(fixedDelay = 60000, scheduler = "maintenanceScheduler")
    public void reclaim() {
        try {
            agents.reclaimCredentials();
            if (rotationPolicy.enabled()) {
                var candidates = rotation.candidates(rotationCursor);
                if (candidates.isEmpty()) rotationCursor = null;
                for (String id : candidates) {
                    try { rotation.rotate(id); }
                    catch (BusinessException unavailable) { LOG.warn("Platform credential rewrap deferred: {}", unavailable.errorCode().code()); }
                    rotationCursor = id;
                }
            }
        } catch (RuntimeException failure) { LOG.warn("Platform credential cleanup deferred: {}", failure.getClass().getSimpleName()); }
    }

    public void run(String id) {
        try {
            var dispatch = store.claim(id);
            if (dispatch == null) return;
            String error = null;
            String receipt = null;
            try {
                var model = dispatch.target().model();
                int tokens = Math.min(model.maxOutputTokens(), model.thinkingMode() == ModelThinkingMode.ON ? 2048 : 512);
                Integer thinking = model.thinkingBudgetTokens() == null ? null : Math.min(model.thinkingBudgetTokens(), Math.max(1024, tokens - 1));
                var request = ChatRequest.builder().messages(List.of(SystemMessage.from(dispatch.instructions()), UserMessage.from(prompts.check(dispatch.role()))))
                    .parameters(DefaultChatRequestParameters.builder().build()).build();
                var result = driver.call(dispatch.target(), request, new ModelInvocationLimitsDTO(tokens, thinking), 65536, dispatch.deadline());
                receipt = definitions.encode(usage.receipt(result.response().tokenUsage(), dispatch.price()));
                if (result.response().aiMessage().hasToolExecutionRequests()) error = "PLATFORM_CHECK_INVALID_RESPONSE";
                else definitions.validateCheck(dispatch.role(), result.response().aiMessage().text());
            } catch (BusinessException failure) { error = failure.errorCode().code(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); error = "PLATFORM_CHECK_INTERRUPTED"; }
            catch (RuntimeException failure) { error = "PLATFORM_CHECK_INVALID_RESPONSE"; }
            store.finish(id, error, receipt);
        } catch (RuntimeException failure) { LOG.warn("Platform check completion deferred: {}", failure.getClass().getSimpleName()); }
        finally { active.remove(id); }
    }

    @PreDestroy
    public void close() { workers.shutdownNow(); }
}
