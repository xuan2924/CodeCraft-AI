package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.repository.CopilotCommandRepository;
import ai.codecraft.modules.workspace.service.CommandTaskStore;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Execution barriers use only the persisted parent binding, never an exercise's latest manual command. */
@Service
public class CopilotCommandLifecycle {
    private final CopilotCommandRepository commands;
    private final CommandTaskStore children;
    private final Clock clock;
    public CopilotCommandLifecycle(CopilotCommandRepository commands, CommandTaskStore children, Clock clock) {
        this.commands = commands; this.children = children; this.clock = clock;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean waiting(String task) { return commands.waiting(task); }
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean pending(String task) { return commands.pending(task) != null; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void stop(String task) {
        var child = commands.pending(task);
        if (child != null && "WAITING".equals(child.state())) children.requestBoundStop(child.exerciseId(), child.commandTaskId());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPending(String task, String reason) { commands.cancelPending(task, reason, clock.instant()); }
}
