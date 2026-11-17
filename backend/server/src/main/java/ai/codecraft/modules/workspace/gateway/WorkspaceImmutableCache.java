package ai.codecraft.modules.workspace.gateway;

import com.google.protobuf.MessageLite;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Only verified committed revision reads. Protobuf values are immutable; no mutable working trees. */
public final class WorkspaceImmutableCache {
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final long TTL_MILLIS = 60000;
    private final Clock clock;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private int bytes;

    public WorkspaceImmutableCache(Clock clock) { this.clock = clock; }

    public <T extends MessageLite> T read(Key key, Class<T> type, Supplier<T> load, Predicate<T> valid) {
        synchronized (this) {
            prune();
            Entry entry = entries.get(key);
            if (entry != null) {
                if (!type.isInstance(entry.value()) || !valid.test(type.cast(entry.value()))) {
                    throw new ai.codecraft.common.exception.BusinessException(ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
                }
                return type.cast(entry.value());
            }
        }
        T value = load.get();
        if (!valid.test(value)) throw new ai.codecraft.common.exception.BusinessException(ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
        int size = value.getSerializedSize() + key.request().getSerializedSize() + key.exercise().length() * 2 + key.instance().length() * 2;
        if (size <= MAX_BYTES) {
            synchronized (this) {
                prune();
                Entry previous = entries.remove(key);
                if (previous != null) bytes -= previous.bytes();
                while (entries.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES) {
                    var first = entries.entrySet().iterator();
                    bytes -= first.next().getValue().bytes(); first.remove();
                }
                entries.put(key, new Entry(value, size, clock.millis() + TTL_MILLIS));
                bytes += size;
            }
        }
        return value;
    }

    private void prune() {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.expires() <= clock.millis()) { bytes -= entry.bytes(); iterator.remove(); }
        }
    }

    public record Key(String exercise, String instance, long revision, MessageLite request) {
        public Key {
            if (exercise == null || exercise.isBlank() || instance == null || instance.isBlank() || revision < 0 || request == null) {
                throw new IllegalArgumentException("Immutable cache requires exercise, instance and revision");
            }
            boolean matches = switch (request) {
                case ai.codecraft.workspace.v1.ReadWorkspaceFileRequest read -> instance.equals(read.getWorkspaceInstanceId()) && revision == read.getExpectedRevision();
                case ai.codecraft.workspace.v1.GrepWorkspaceRequest grep -> instance.equals(grep.getWorkspaceInstanceId()) && revision == grep.getExpectedRevision();
                default -> false;
            };
            if (!matches) throw new IllegalArgumentException("Cache key must match immutable RPC request");
        }
    }
    private record Entry(MessageLite value, int bytes, long expires) { }
}
