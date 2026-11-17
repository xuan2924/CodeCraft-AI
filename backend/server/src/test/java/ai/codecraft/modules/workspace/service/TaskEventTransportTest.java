package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import ai.codecraft.modules.workspace.config.TaskStreamProperties;
import ai.codecraft.modules.workspace.model.dto.TaskStreamBatchDTO;
import ai.codecraft.modules.workspace.stream.TaskEventTransport;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskEventTransportTest {
    @Test
    void commitHintDuringAReadIsNotLostAndEveryWakeReauthorizes() throws Exception {
        var hub = new TransientTaskStreamHub();
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var context = mock(AsyncContext.class);
        var output = mock(ServletOutputStream.class);
        when(request.startAsync()).thenReturn(context);
        when(response.getOutputStream()).thenReturn(output);
        when(output.isReady()).thenReturn(true);
        var listener = new AtomicReference<WriteListener>();
        doAnswer(call -> { listener.set(call.getArgument(0)); return null; }).when(output).setWriteListener(any());
        var initialRead = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        var followup = new CountDownLatch(1);
        var reads = new AtomicInteger();
        TaskStreamSource source = (session, exercise, task, cursor, deliver) -> {
            int count = reads.incrementAndGet();
            if (count == 2) {
                initialRead.countDown();
                try { assertTrue(releaseRead.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            if (count >= 3) followup.countDown();
            return new TaskStreamBatchDTO("user", "cursor", new byte[0]);
        };
        var transport = new TaskEventTransport(mock(TaskStreamStore.class), new TaskStreamProperties(5, 32, 1000, 15000, 5000), hub);
        try {
            transport.open("session", "exercise", "task", null, null, request, response, source);
            listener.get().onWritePossible();
            assertTrue(initialRead.await(3, TimeUnit.SECONDS));
            hub.signal("task");
            releaseRead.countDown();
            assertTrue(followup.await(3, TimeUnit.SECONDS));
        } finally { releaseRead.countDown(); transport.shutdown(); }
        verify(context).complete();
    }
}
