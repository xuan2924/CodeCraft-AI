package ai.codecraft.modules.workspace.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.workspace.v1.ReadWorkspaceFileRequest;
import ai.codecraft.workspace.v1.ReadWorkspaceFileResponse;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkspaceImmutableCacheTest {
    private WorkspaceImmutableCache.Key key(String exercise, long revision, String path) {
        return new WorkspaceImmutableCache.Key(exercise, "instance", revision, ReadWorkspaceFileRequest.newBuilder()
            .setWorkspaceInstanceId("instance").setExpectedRevision(revision).setPath(path).build());
    }
    private ReadWorkspaceFileResponse load(WorkspaceImmutableCache cache, WorkspaceImmutableCache.Key key, AtomicInteger loads) {
        return cache.read(key, ReadWorkspaceFileResponse.class, () -> {
            loads.incrementAndGet();
            return ReadWorkspaceFileResponse.newBuilder().setRevision(key.revision()).setContent("committed").build();
        }, value -> value.getRevision() == key.revision());
    }
    @Test void revisionExerciseAndArgumentsNeverAliasAndTtlExpires() {
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        var cache = new WorkspaceImmutableCache(clock);
        var loads = new AtomicInteger();
        var key = key("exercise", 0, "a");
        load(cache, key, loads); load(cache, key, loads);
        assertThat(loads).hasValue(1);
        load(cache, key("other", 0, "a"), loads);
        load(cache, key("exercise", 1, "a"), loads);
        load(cache, key("exercise", 0, "b"), loads);
        assertThat(loads).hasValue(4);
        when(clock.millis()).thenReturn(60000L);
        load(cache, key, loads);
        assertThat(loads).hasValue(5);
    }
    @Test void boundedLruAndWrongVersionIsNeverCached() {
        var cache = new WorkspaceImmutableCache(Clock.systemUTC());
        var loads = new AtomicInteger();
        for (int i = 0; i < 513; i++) load(cache, key("exercise", i, "a"), loads);
        load(cache, key("exercise", 0, "a"), loads);
        assertThat(loads).hasValue(514);
        var key = key("exercise", 999, "a");
        assertThatThrownBy(() -> cache.read(key, ReadWorkspaceFileResponse.class,
            () -> ReadWorkspaceFileResponse.getDefaultInstance(), value -> value.getRevision() == 999)).hasMessage("WORKSPACE_UNAVAILABLE");
        assertThat(load(cache, key, loads).getRevision()).isEqualTo(999);
        assertThatThrownBy(() -> new WorkspaceImmutableCache.Key("exercise", "instance", 1,
            ReadWorkspaceFileRequest.getDefaultInstance())).isInstanceOf(IllegalArgumentException.class);
    }
}
