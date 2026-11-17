package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.workspace.repository.CommandTaskRepository;
import org.springframework.stereotype.Service;

/** Public workspace boundary: a proxy grant never substitutes for live terminal authority. */
@Service
public class TerminalModelAccessService {
    private final CommandTaskRepository commands;
    private final TerminalStore terminals;
    private final ExerciseModelService models;
    public TerminalModelAccessService(CommandTaskRepository commands, TerminalStore terminals, ExerciseModelService models) {
        this.commands = commands; this.terminals = terminals; this.models = models;
    }
    public record Authority(String task, String exercise, String user, long epoch) { }
    public Authority authorize(String task, String exercise, long epoch) {
        var command = commands.find(task);
        if (command == null || !exercise.equals(command.exerciseId()) || epoch != command.gateEpoch()) throw new IllegalStateException("Proxy authority ended");
        var authorized = terminals.authorize(command.sessionHash(), exercise, task);
        return new Authority(task, exercise, authorized.userId(), epoch);
    }
    public ModelInvocationDTO resolve(Authority authority) {
        authorize(authority.task(), authority.exercise(), authority.epoch());
        var command = commands.find(authority.task());
        return models.resolveForCall(command.sessionHash(), authority.exercise());
    }
}
