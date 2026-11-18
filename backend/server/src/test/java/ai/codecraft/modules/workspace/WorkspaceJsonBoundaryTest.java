package ai.codecraft.modules.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.codecraft.common.config.ApplicationConfiguration;
import ai.codecraft.modules.workspace.config.WorkspaceJsonConfiguration;
import ai.codecraft.modules.workspace.controller.WorkspaceWriteController;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.model.entity.WorkspaceMutationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.service.WorkspaceBatchCodec;
import ai.codecraft.modules.workspace.service.WorkspaceWriteService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceJsonBoundaryTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(ApplicationConfiguration.class, WorkspaceJsonConfiguration.class, WorkspaceBatchCodec.class);

    @Test
    void storedBatchUsesWorkspaceReaderWithoutWeakeningTheIdentityMapper() {
        runner.run(context -> {
            ObjectMapper primary = context.getBean(ObjectMapper.class);
            String content = "x".repeat(1024 * 1024);
            var codec = context.getBean(WorkspaceBatchCodec.class);
            String payload = codec.encode(new FileBatchRequest("0", List.of(new FileChangeRequest("src/main/java/Main.java", "UPDATE", content))));
            var operation = new WorkspaceOperationEntity("operation", "owner", "exercise", "workspace", "FILE_BATCH", "exercise", "key", "hash", "PENDING", Instant.EPOCH);
            var mutation = new WorkspaceMutationEntity("operation", payload, 1, Instant.now().plusSeconds(60), false, null, null, null);
            assertThat(codec.request(operation, mutation).getChanges(0).getContent()).isEqualTo(content);
            assertThrows(JsonProcessingException.class, () -> primary.readValue("\"" + "x".repeat(20000) + "\"", String.class));
            assertThat(primary.writeValueAsString(Instant.EPOCH)).isEqualTo("\"1970-01-01T00:00:00Z\"");
        });
    }

    @Test
    void storedLogTailUsesTheQualifiedWorkspaceMapperThroughThePublicTaskQuery() {
        var identity = mock(ai.codecraft.modules.identity.service.IdentityTransactionService.class);
        var exercises = mock(ai.codecraft.modules.workspace.repository.ExerciseRepository.class);
        var tasks = mock(ai.codecraft.modules.workspace.repository.TestTaskRepository.class);
        var streams = mock(ai.codecraft.modules.workspace.repository.TaskStreamRepository.class);
        String exercise = "11111111-1111-4111-8111-111111111111";
        String task = "22222222-2222-4222-8222-222222222222";
        String workspace = "33333333-3333-4333-8333-333333333333";
        when(identity.authorizePrivateDelivery("session")).thenReturn(new ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO("owner", "USER", false, 1, Instant.MAX));
        when(exercises.find(exercise)).thenReturn(new ai.codecraft.modules.workspace.model.entity.ExerciseEntity(exercise, "owner", "problem", workspace, "prepare", "ENDED", 0L, "[]", 1, Instant.EPOCH, null, null, null, null, null));
        when(streams.find(task)).thenReturn(new ai.codecraft.modules.workspace.model.entity.TaskStreamEntity(task, "44444444-4444-4444-8444-444444444444", 0, 1));
        runner.withBean(ai.codecraft.modules.workspace.service.SandboxRoutingStore.class, () -> mock(ai.codecraft.modules.workspace.service.SandboxRoutingStore.class))
            .withBean(ai.codecraft.modules.workspace.config.ExecutionProperties.class, () -> new ai.codecraft.modules.workspace.config.ExecutionProperties(4))
            .withBean(ai.codecraft.modules.identity.service.IdentityTransactionService.class, () -> identity)
            .withBean(ai.codecraft.modules.identity.service.IdentitySecrets.class, () -> mock(ai.codecraft.modules.identity.service.IdentitySecrets.class))
            .withBean(ai.codecraft.modules.workspace.repository.ExerciseRepository.class, () -> exercises)
            .withBean(ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository.class, () -> mock(ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository.class))
            .withBean(ai.codecraft.modules.workspace.repository.TestTaskRepository.class, () -> tasks)
            .withBean(ai.codecraft.modules.workspace.repository.ProblemRepository.class, () -> mock(ai.codecraft.modules.workspace.repository.ProblemRepository.class))
            .withBean(ai.codecraft.modules.workspace.repository.TaskStreamRepository.class, () -> streams)
            .withBean(ai.codecraft.modules.workspace.service.TaskStreamPublisher.class, () -> mock(ai.codecraft.modules.workspace.service.TaskStreamPublisher.class))
            .withBean(ai.codecraft.modules.workspace.repository.FinalizationRepository.class, () -> mock(ai.codecraft.modules.workspace.repository.FinalizationRepository.class))
            .withBean(ai.codecraft.modules.workspace.config.StorageProperties.class, () -> new ai.codecraft.modules.workspace.config.StorageProperties(512L * 1024 * 1024, 256L * 1024 * 1024, 20))
            .withBean(ai.codecraft.modules.workspace.service.WorkspaceMaintenanceStore.class, () -> mock(ai.codecraft.modules.workspace.service.WorkspaceMaintenanceStore.class))
            .withBean(ai.codecraft.modules.workspace.service.StorageCapacityStore.class, () -> mock(ai.codecraft.modules.workspace.service.StorageCapacityStore.class))
            .withUserConfiguration(ai.codecraft.modules.workspace.service.TestTaskStore.class).run(context -> {
                ObjectMapper primary = context.getBean(ObjectMapper.class);
                var logs = java.util.stream.IntStream.range(0, 64).mapToObj(index -> new ai.codecraft.modules.workspace.model.dto.TestLogResponse("stdout", "x".repeat(16384))).toList();
                var snapshot = new ai.codecraft.modules.workspace.model.dto.TestTaskResponse(task, exercise, task, workspace, "0", "base", "STOPPED", "USER_STOPPED",
                    Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, true, "SUCCEEDED", "SUCCEEDED", "0", false, logs,
                    new ai.codecraft.modules.workspace.model.dto.TestProblemResponse("cart-total", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64"), null);
                when(tasks.lock(task)).thenReturn(new ai.codecraft.modules.workspace.model.entity.TestTaskEntity(task, task, exercise, workspace, 0, 1, Instant.MAX,
                    "base", "STOPPED", true, true, primary.writeValueAsString(snapshot), "host", 1));
                assertThat(context.getBean(ai.codecraft.modules.workspace.service.TestTaskStore.class).query("session", exercise, task).logs()).hasSize(64);
            });
    }

    @Test
    void moduleOwnedRequestPolicyExpandsOnlyTheFileBatchBoundary() {
        runner.run(context -> {
            var bodies = context.getBean(ai.codecraft.common.web.RequestBodyAdmission.class);
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            request.setMethod("POST"); request.setServletPath("/api/auth/login");
            assertThat(bodies.maximumBytes(request)).isEqualTo(16384);
            request.setServletPath("/api/exercises/11111111-1111-4111-8111-111111111111/file-batches");
            assertThat(bodies.maximumBytes(request)).isEqualTo(64L * 1024 * 1024);
            request.setMethod("GET");
            assertThat(bodies.maximumBytes(request)).isEqualTo(16384);
        });
    }

    @Test
    void httpConverterAcceptsOneMiBOfEscapedTextWithoutChangingOtherTypes() {
        runner.run(context -> {
            ObjectMapper primary = context.getBean(ObjectMapper.class);
            var converter = new MappingJackson2HttpMessageConverter(primary);
            context.getBean(WorkspaceJsonConfiguration.class).extendMessageConverters(List.of(converter));
            var writes = mock(WorkspaceWriteService.class);
            String exercise = "11111111-1111-4111-8111-111111111111";
            when(writes.batch(any(), anyString(), any(), any(), any())).thenReturn(new WorkspaceOperationResponse("operation", exercise, "SUCCEEDED", "/api/operations/operation"));
            var mvc = MockMvcBuilders.standaloneSetup(new WorkspaceWriteController(writes)).setMessageConverters(converter).build();
            String payload = primary.writeValueAsString(new FileBatchRequest("0", List.of(new FileChangeRequest("src/main/java/Main.java", "UPDATE", Character.toString(1).repeat(1024 * 1024)))));
            mvc.perform(post("/api/exercises/" + exercise + "/file-batches").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
            assertThrows(JsonProcessingException.class, () -> primary.readValue("\"" + "x".repeat(20000) + "\"", String.class));
        });
    }
}
