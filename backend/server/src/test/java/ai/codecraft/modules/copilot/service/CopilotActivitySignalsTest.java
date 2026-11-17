package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import ai.codecraft.infrastructure.tasks.TaskWakeup;
import ai.codecraft.infrastructure.tasks.TaskWakeupPublisher;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CopilotActivitySignalsTest {
    @Test void committedChangesNotifyTheTaskStreamAndCopilotDispatcherOnlyAfterCommit() throws Exception {
        var events = new LinkedBlockingQueue<TaskWakeup>();
        var publisher = new TaskWakeupPublisher(event -> events.add((TaskWakeup) event));
        var streams = new TransientTaskStreamHub();
        var delivered = new ArrayList<String>();
        try (var subscription = streams.subscribe(delivered::add)) {
            var signals = new CopilotActivitySignals(publisher, streams);
            TransactionSynchronizationManager.initSynchronization();
            try {
                signals.changed("child-task");
                assertThat(delivered).isEmpty();
                assertThat(events).isEmpty();
                for (var callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
                assertThat(delivered).containsExactly("child-task");
                assertThat(events.poll(2, TimeUnit.SECONDS)).isEqualTo(new TaskWakeup(TaskWakeup.Lane.COPILOT));
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
        } finally { publisher.close(); }
    }

    @Test void rolledBackChangesDoNotNotifyEitherChannel() throws Exception {
        var events = new LinkedBlockingQueue<TaskWakeup>();
        var publisher = new TaskWakeupPublisher(event -> events.add((TaskWakeup) event));
        var streams = new TransientTaskStreamHub();
        var delivered = new ArrayList<String>();
        try (var subscription = streams.subscribe(delivered::add)) {
            TransactionSynchronizationManager.initSynchronization();
            try { new CopilotActivitySignals(publisher, streams).changed("btw-task"); }
            finally { TransactionSynchronizationManager.clearSynchronization(); }
            assertThat(delivered).isEmpty();
            assertThat(events).isEmpty();
        } finally { publisher.close(); }
    }

    @Test void coordinatorIgnoresOtherLanesAndDispatchesCopilotHints() {
        var coordinator = mock(CopilotCoordinator.class, CALLS_REAL_METHODS);
        doNothing().when(coordinator).dispatch();
        coordinator.wake(new TaskWakeup(TaskWakeup.Lane.INTERVIEWER));
        coordinator.wake(new TaskWakeup(TaskWakeup.Lane.EVALUATION));
        verify(coordinator, never()).dispatch();
        coordinator.wake(new TaskWakeup(TaskWakeup.Lane.COPILOT));
        verify(coordinator).dispatch();
    }
}
