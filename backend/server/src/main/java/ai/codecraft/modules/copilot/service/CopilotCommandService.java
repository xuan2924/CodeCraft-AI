package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandResultDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandsResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotToolExecutionDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotCommandEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotCommandRepository;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.CommandTaskCoordinator;
import ai.codecraft.modules.workspace.service.CommandTaskStore;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** External capacity measurement and complete evidence codecs stay outside authorization transactions. */
@Service
public class CopilotCommandService {
    public static final int MAX_ARGUMENT_BYTES = 32768;
    private final CopilotCommandStore store;
    private final CopilotCommandRepository commands;
    private final CommandTaskStore children;
    private final CommandTaskCoordinator coordinator;
    private final StorageCapacityService capacity;
    private final CopilotCommandResultFormatter formatter;
    private final ObjectMapper json;
    private final ObjectReader input;
    public CopilotCommandService(CopilotCommandStore store, CopilotCommandRepository commands, CommandTaskStore children,
        CommandTaskCoordinator coordinator, StorageCapacityService capacity, CopilotCommandResultFormatter formatter,
        @Qualifier("workspaceJson") ObjectMapper json) {
        this.store = store; this.commands = commands; this.children = children; this.coordinator = coordinator;
        this.capacity = capacity; this.formatter = formatter; this.json = json;
        input = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public CopilotToolExecutionDTO execute(CopilotTaskEntity task, String worker, CopilotToolEntity tool) throws JsonProcessingException {
        try {
            var request = parse(tool.arguments());
            if (store.register(task.id(), worker, tool.id(), request)) return new CopilotToolExecutionDTO(true, null);
            return rejected("COMMAND_PREVIOUSLY_REJECTED");
        } catch (BusinessException rejected) { return rejected(rejected.errorCode().code()); }
    }

    public CopilotCommandsResponse recent(String session, String taskId, long before) {
        var page = store.page(session, taskId, before);
        var selected = page.size() > 20 ? page.subList(0, 20) : page;
        return new CopilotCommandsResponse(selected.stream().map(this::response).toList(), page.size() > 20 ? Long.toString(selected.getLast().sequence()) : null);
    }
    public CopilotCommandResponse read(String session, String id) { return response(store.read(session, id)); }

    public CopilotCommandResponse decide(String session, String id, String credential, String key, CopilotProposalDecisionRequest request) {
        var previous = store.replay(session, id, key, request);
        if (previous != null) return response(previous);
        if ("REJECT".equals(request.decision())) return response(store.decide(session, id, credential, key, request, null, null));
        var source = store.read(session, id);
        try {
            return response(admit(session, source.exerciseId(), measured -> store.decide(session, id, credential, key, request, measured, null)));
        } catch (BusinessException rejected) {
            if (rejected.errorCode().status() >= 500) throw rejected;
            // The failed admission transaction rolls back; record its definite result in a fresh, reauthorized decision.
            return response(store.decide(session, id, credential, key, request, null, rejected.errorCode().code()));
        }
    }

    public List<String> automaticPending() { return commands.automaticPending(); }
    public void approveAuto(String taskId) {
        var source = store.autoCandidate(taskId);
        if (source == null) return;
        try {
            admit(source.sessionHash(), source.command().exerciseId(), measured -> {
                store.approveAuto(taskId, source.command().id(), measured, null);
                return null;
            });
        } catch (BusinessException rejected) {
            if (rejected.errorCode().status() >= 500) throw rejected;
            store.approveAuto(taskId, source.command().id(), null, rejected.errorCode().code());
        }
    }

    public void recoverTask(String taskId) {
        var source = store.recovery(taskId);
        if (source == null) return;
        try {
            var result = json.readValue(source.evidence().payload(), CommandTaskResponse.class);
            store.complete(source, json.writeValueAsString(formatter.format(source, result)));
        } catch (JsonProcessingException invalid) { throw new IllegalStateException("Cannot preserve confirmed command result", invalid); }
    }

    private <T> T admit(String session, String exercise, Function<StorageMeasurement, T> action) {
        try { return capacity.admit(session, exercise, action); }
        catch (BusinessException rejection) {
            if (rejection.errorCode() != WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED || !coordinator.pruneForAdmission(exercise)) throw rejection;
            return capacity.admit(session, exercise, action);
        }
    }

    CommandStartRequest parse(String arguments) {
        if (arguments == null || arguments.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) throw invalid();
        try {
            JsonNode value = input.readValue(arguments);
            if (!value.isObject() || value.size() != 3 || !value.path("expected_revision").isTextual()
                || !value.path("command").isTextual() || !value.path("directory").isTextual()) throw invalid();
            var request = new CommandStartRequest(value.get("command").textValue(), value.get("directory").textValue(), value.get("expected_revision").textValue());
            children.validate(request);
            return request;
        } catch (JsonProcessingException invalid) { throw invalid(); }
    }

    private CopilotCommandResponse response(CopilotCommandEntity command) {
        try {
            var result = command.resultPayload() == null ? null : json.readValue(command.resultPayload(), CopilotCommandResultDTO.class);
            return new CopilotCommandResponse(command.id(), Long.toString(command.sequence()), command.taskId(), command.exerciseId(), command.workspaceInstanceId(),
                Long.toString(command.baseRevision()), command.commandText(), command.workingDirectory(), command.mode(), Long.toString(command.modeVersion()),
                command.state(), command.commandTaskId(), command.decision(), command.reason(), result, command.createdAt(), command.decidedAt(), command.completedAt());
        } catch (JsonProcessingException invalid) { throw new IllegalStateException("Invalid command result checkpoint", invalid); }
    }
    private CopilotToolExecutionDTO rejected(String code) throws JsonProcessingException {
        return new CopilotToolExecutionDTO(false, json.writeValueAsString(new CopilotCommandResultDTO(code, "", null, false, "0")));
    }
    private BusinessException invalid() { return new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST); }
}
