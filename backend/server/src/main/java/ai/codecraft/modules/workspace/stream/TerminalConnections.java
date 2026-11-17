package ai.codecraft.modules.workspace.stream;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded ownership of live terminal transports; lifecycle shutdown is independent of browser input. */
@Component
public class TerminalConnections {
    private record Connection(String exercise, Runnable stop) { }
    private final Map<String, Connection> active = new HashMap<>();

    public synchronized boolean register(String task, String exercise, Runnable stop) {
        if (active.size() >= 64 || active.containsKey(task)) return false;
        active.put(task, new Connection(exercise, stop));
        return true;
    }
    public synchronized void remove(String task) { active.remove(task); }
    public void closeExercise(String exercise) {
        java.util.List<Runnable> stops;
        synchronized (this) { stops = active.values().stream().filter(value -> value.exercise().equals(exercise)).map(Connection::stop).toList(); }
        stops.forEach(Runnable::run);
    }
}
