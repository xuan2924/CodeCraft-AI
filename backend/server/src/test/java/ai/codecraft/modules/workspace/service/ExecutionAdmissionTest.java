package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.ExecutionProperties;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.dto.StartTestRequest;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.repository.CommandTaskRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ExecutionAdmissionTest {
    @Test void manualAndCommandAdmissionShareExerciseExclusionAndBoundedPlatformSlots() {
        for (boolean command : new boolean[]{false, true}) {
            for (int occupied : new int[]{0, 3, 4}) {
                for (int sameExercise : new int[]{0, 1}) {
                    var identity = mock(IdentityTransactionService.class);
                    var secrets = mock(IdentitySecrets.class);
                    var exercises = mock(ExerciseRepository.class);
                    var writes = mock(WorkspaceWriteRepository.class);
                    var tasks = mock(TestTaskRepository.class);
                    var problems = mock(ProblemRepository.class);
                    var capacity = mock(StorageCapacityStore.class);
                    var now = Instant.parse("2026-01-01T00:00:00Z");
                    var clock = Clock.fixed(now, ZoneOffset.UTC);
                    when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60)));
                    var exercise = mock(ExerciseEntity.class);
                    when(exercise.userId()).thenReturn("owner");
                    when(exercise.state()).thenReturn("ACTIVE");
                    when(exercise.deadlineAt()).thenReturn(now.plusSeconds(600));
                    when(exercise.revision()).thenReturn(1L);
                    when(exercise.problemVersionId()).thenReturn("problem");
                    when(exercise.workspaceInstanceId()).thenReturn("workspace");
                    when(exercises.find("exercise")).thenReturn(exercise);
                    when(writes.lock("exercise")).thenReturn(new WorkspaceControlEntity("exercise", 1, 2L, "OPEN", IdentitySecrets.digest("c".repeat(43)), null, null));
                    when(secrets.fingerprint(any())).thenReturn("fingerprint");
                    when(problems.executionAvailable("problem")).thenReturn(true);
                    when(tasks.occupied()).thenReturn(occupied);
                    when(tasks.occupiedExercise("exercise")).thenReturn(sameExercise);
                    var json = JsonMapper.builder().build();
                    var tests = new TestTaskStore(new ExecutionProperties(4), identity, secrets, exercises, writes, tasks, problems, clock, json,
                        mock(TaskStreamRepository.class), mock(TaskStreamPublisher.class), mock(FinalizationRepository.class),
                        new StorageProperties(512L * 1024 * 1024, 256L * 1024 * 1024, 20), mock(WorkspaceMaintenanceStore.class), capacity);
                    var commands = new CommandTaskStore(new ExecutionProperties(4), identity, secrets, exercises, writes, tasks,
                        mock(CommandTaskRepository.class), problems, capacity, json, clock, mock(ExerciseDataService.class), mock(WorkspaceHistoryLedger.class));
                    java.util.concurrent.Callable<Object> admit = () -> command
                        ? commands.accept("session", "exercise", "key", "c".repeat(43), new CommandStartRequest("true", ".", "1"), null)
                        : tests.accept("session", "exercise", "key", "c".repeat(43), new StartTestRequest("1", "base"), null);
                    if (sameExercise != 0 || occupied >= 4) {
                        assertThatThrownBy(admit::call).isInstanceOf(BusinessException.class)
                            .hasMessage(command ? "COMMAND_SLOT_BUSY" : "TEST_SLOT_BUSY");
                    } else {
                        try { assertThat(admit.call()).isNotNull(); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    }
                    var ordered = inOrder(tasks);
                    ordered.verify(tasks).lockAdmission();
                    ordered.verify(tasks).occupiedExercise("exercise");
                    if (sameExercise == 0) ordered.verify(tasks).occupied();
                }
            }
        }
    }
}
