package ai.codecraft.modules.copilot.service;

import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import ai.codecraft.infrastructure.tasks.TaskWakeup;
import ai.codecraft.infrastructure.tasks.TaskWakeupPublisher;
import org.springframework.stereotype.Component;

/** Disposable commit hints; durable claims and fallback scans remain authoritative. */
@Component
public class CopilotActivitySignals {
    private final TaskWakeupPublisher wakeups;
    private final TransientTaskStreamHub streams;

    public CopilotActivitySignals(TaskWakeupPublisher wakeups, TransientTaskStreamHub streams) {
        this.wakeups = wakeups; this.streams = streams;
    }

    public void changed(String taskId) {
        streams.afterCommit(taskId);
        wake();
    }

    public void wake() { wakeups.afterCommit(TaskWakeup.Lane.COPILOT); }
}
