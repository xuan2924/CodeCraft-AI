package ai.codecraft.modules.workspace.stream;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded ownership of live terminal transports; lifecycle shutdown is independent of browser input. */
@Component
public class TerminalConnections {
    private record Connection(String exercise, String token, Runnable stop) { }
    private final Map<String, Connection> active = new HashMap<>();
    private final ai.codecraft.infrastructure.ratelimit.ConcurrentLeaseLimiter leases;

    public TerminalConnections() { this(new ai.codecraft.infrastructure.ratelimit.ConcurrentLeaseLimiter(java.time.Clock.systemUTC())); }
    @org.springframework.beans.factory.annotation.Autowired
    public TerminalConnections(ai.codecraft.infrastructure.ratelimit.ConcurrentLeaseLimiter leases) { this.leases = leases; }

    public synchronized boolean register(String task, String exercise, Runnable stop) {
        if (active.size() >= 64 || active.containsKey(task)) return false;
        String token = java.util.UUID.randomUUID().toString();
        if (!leases.acquire(task, token)) return false;
        active.put(task, new Connection(exercise, token, stop));
        return true;
    }
    public synchronized void remove(String task) {
        Connection connection = active.remove(task);
        if (connection != null) {
            try { leases.release(task, connection.token()); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(TerminalConnections.class).warn("Terminal lease release unavailable; expiry will reclaim slot");
            }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    public void renew() {
        Map<String, Connection> snapshot;
        synchronized (this) { snapshot = Map.copyOf(active); }
        snapshot.forEach((task, connection) -> {
            boolean renewed;
            try { renewed = leases.renew(task, connection.token()); }
            catch (RuntimeException unavailable) { renewed = false; }
            if (!renewed) connection.stop().run();
        });
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        java.util.List<Runnable> stops;
        synchronized (this) { stops = active.values().stream().map(Connection::stop).toList(); }
        stops.forEach(Runnable::run);
    }
    public void closeExercise(String exercise) {
        java.util.List<Runnable> stops;
        synchronized (this) { stops = active.values().stream().filter(value -> value.exercise().equals(exercise)).map(Connection::stop).toList(); }
        stops.forEach(Runnable::run);
    }
}
