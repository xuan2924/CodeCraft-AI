package ai.codecraft.modules.modelaccess.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.modelaccess.config.ModelCheckWorkerProperties;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ModelCheckCoordinatorTest {
    @Test
    void saturationDoesNotCreateExtraWorkersAndShutdownInterruptsOwnedWork() throws Exception {
        var checks = mock(ModelCheckRepository.class);
        var runner = mock(ModelCheckRunner.class);
        when(checks.queued()).thenReturn(List.of("first", "second"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        doAnswer(call -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException stopped) { interrupted.set(true); Thread.currentThread().interrupt(); }
            return null;
        }).when(runner).run("first");
        try (var coordinator = new ModelCheckCoordinator(checks, runner, new ModelCheckWorkerProperties(1))) {
            coordinator.dispatch();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            coordinator.dispatch();
            verify(runner).run("first");
            verify(runner, never()).run("second");
        } finally { release.countDown(); }
        assertTrue(interrupted.get());
    }
}
