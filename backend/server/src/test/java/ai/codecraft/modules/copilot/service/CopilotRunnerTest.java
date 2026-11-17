package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.ToolOutcome;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTurnEntity;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Timeout(10)
class CopilotRunnerTest {
    private final CopilotExecutionStore store = mock(CopilotExecutionStore.class);
    private final StorageCapacityService capacity = mock(StorageCapacityService.class);
    private final CopilotToolExecutor tools = mock(CopilotToolExecutor.class);
    private final CopilotRunner runner = new CopilotRunner(store, capacity, mock(ModelCallDriver.class), tools,
        mock(CopilotProperties.class), mock(ModelCallProperties.class), mock(CopilotContextService.class), mock(TransientTaskStreamHub.class), new ObjectMapper());
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicInteger position = new AtomicInteger();
    private final List<Integer> completed = new ArrayList<>();

    private CopilotTaskEntity task() {
        return new CopilotTaskEntity("task", "exercise", "user", "session", "key", "fingerprint", "RUNNING", null,
            stopped.get(), "worker", Instant.MAX, Instant.EPOCH, null, "conversation", "MAIN");
    }
    private CopilotToolEntity tool(ToolExecutionRequest request, int index) {
        return new CopilotToolEntity("tool" + index, "turn", "task", "exercise", index, request.id(), request.name(), request.arguments(), "RUNNING", null, Instant.EPOCH, null);
    }
    private void setup(List<String> names) {
        var requests = new ArrayList<ToolExecutionRequest>();
        for (int index = 0; index < names.size(); index++) requests.add(ToolExecutionRequest.builder().id("call" + index).name(names.get(index)).arguments("{}").build());
        when(store.claim("task", "worker")).thenReturn(task());
        when(store.current("task", "worker")).thenAnswer(call -> finished.get() ? null : task());
        when(store.turn("task", "worker")).thenReturn(new CopilotTurnEntity("turn", "task", "exercise", 1, "TOOLS", null, null, 0, 0, null, null, 0, "assistant", 0, Instant.EPOCH, Instant.MAX, null, null));
        when(store.assistant("task", "worker")).thenReturn(ChatMessageSerializer.messageToJson(AiMessage.from(requests)));
        when(capacity.admit(eq("session"), eq("exercise"), any())).thenAnswer(call -> {
            Function<StorageMeasurement, ?> acceptance = call.getArgument(2);
            return acceptance.apply(null);
        });
        when(store.beginReadTools(eq("task"), eq("worker"), eq("turn"), anyList(), any())).thenAnswer(call -> {
            List<ToolExecutionRequest> window = call.getArgument(3);
            assertThat(window.size()).isBetween(2, 4);
            assertThat(window).allMatch(request -> CopilotToolCatalog.find(request.name()).rereadable());
            var result = new ArrayList<CopilotToolEntity>();
            for (int index = 0; index < window.size(); index++) result.add(tool(window.get(index), position.get() + index));
            return result;
        });
        when(store.beginTool(eq("task"), eq("worker"), eq("turn"), any(), any())).thenAnswer(call -> tool(call.getArgument(3), position.get()));
        when(store.completeTool(eq("task"), eq("worker"), any(), anyString(), anyBoolean())).thenAnswer(call -> {
            CopilotToolEntity tool = call.getArgument(2);
            assertThat(tool.position()).isEqualTo(position.getAndIncrement());
            var result = (ToolExecutionResultMessage) ChatMessageDeserializer.messageFromJson(call.getArgument(3));
            assertThat(result.id()).isEqualTo(tool.callId());
            completed.add(tool.position());
            if (call.<Boolean>getArgument(4)) finished.set(true);
            return true;
        });
        doAnswer(call -> { finished.set(true); return null; }).when(store).finish(anyString(), anyString(), anyString(), any());
    }

    @Test void consecutiveReadsOverlapButPersistInOrderAndWritesRemainSerial() throws Exception {
        setup(List.of("read", "grep", "find", "read_source", "read_evidence", "bash", "load_skill", "web_read"));
        var entered = new CountDownLatch(4);
        var laterFinished = new CountDownLatch(3);
        when(tools.execute(any(), eq("worker"), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            CopilotToolEntity tool = call.getArgument(2);
            if (tool.position() < 4) {
                entered.countDown();
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                if (tool.position() == 0) assertThat(laterFinished.await(3, TimeUnit.SECONDS)).isTrue();
                else laterFinished.countDown();
            }
            if (tool.name().equals("bash")) assertThat(position.get()).isEqualTo(5);
            return new ToolOutcome.Completed(ToolOutcome.Code.OK, "{\"code\":\"OK\"}");
        });
        runner.run("task", "worker");
        assertThat(completed).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test void stopCancelsInFlightReadsAndNeverDispatchesTheFollowingWrite() throws Exception {
        setup(List.of("read", "grep", "bash"));
        var entered = new CountDownLatch(2);
        var cancelled = new CountDownLatch(2);
        when(tools.execute(any(), eq("worker"), any())).thenAnswer(call -> {
            entered.countDown();
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                stopped.set(true);
                new CountDownLatch(1).await();
                throw new AssertionError("Expected cancellation");
            } finally { cancelled.countDown(); }
        });
        runner.run("task", "worker");
        assertThat(cancelled.getCount()).isZero();
        assertThat(completed).isEmpty();
        verify(store).finish("task", "worker", "STOPPED", "USER_STOP");
        verify(store, never()).beginTool(any(), any(), any(), any(), any());
    }

    @Test void interruptedReadCancelsItsSiblingAndPreservesWorkerInterruption() throws Exception {
        setup(List.of("read", "grep", "bash"));
        var entered = new CountDownLatch(2);
        var cancelled = new CountDownLatch(1);
        when(tools.execute(any(), eq("worker"), any())).thenAnswer(call -> {
            CopilotToolEntity tool = call.getArgument(2);
            entered.countDown();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            if (tool.position() == 0) throw new InterruptedException("fixture interruption");
            try { new CountDownLatch(1).await(); }
            finally { cancelled.countDown(); }
            throw new AssertionError("Expected cancellation");
        });
        try {
            runner.run("task", "worker");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(cancelled.getCount()).isZero();
            assertThat(completed).isEmpty();
            verify(store).finish("task", "worker", "INTERRUPTED", null);
            verify(store, never()).beginTool(any(), any(), any(), any(), any());
        } finally { Thread.interrupted(); }
    }

    @Test void steerIsAppliedAfterTheAdmittedReadWindowBeforeTheFollowingWrite() throws Exception {
        setup(List.of("read", "grep", "bash"));
        when(tools.execute(any(), eq("worker"), any())).thenReturn(new ToolOutcome.Completed(ToolOutcome.Code.OK, "{\"code\":\"OK\"}"));
        when(store.steerAtBoundary("task", "worker")).thenAnswer(call -> {
            if (position.get() != 2) return false;
            finished.set(true);
            return true;
        });
        runner.run("task", "worker");
        assertThat(completed).containsExactly(0, 1);
        verify(store, never()).beginTool(any(), any(), any(), any(), any());
    }
}
