package prototype;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.agentic.scope.AgenticScopePersister;
import dev.langchain4j.agentic.scope.AgenticSystemSuspendedException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.MemoryId;

/**
 * THROWAWAY: ONE fixed tool batch, sequential tools, checkpoint only at the
 * approval boundary.
 */
public class BatchRecoveryPoc {
    public interface Workflow extends AgenticScopeAccess {
        @Agent
        String run(@MemoryId String id);
    }

    static class Batch extends RecoveryPoc.Fixture {
        Batch(Path path) {
            super(path);
        }

        Properties data() throws IOException {
            return RecoveryPoc.load(directory.resolve("workspace.properties"));
        }

        void store(Properties data) throws IOException {
            RecoveryPoc.save(directory.resolve("workspace.properties"), data);
        }

        void count(String key) throws IOException {
            Properties data = data();
            data.setProperty(key, Integer.toString(Integer.parseInt(data.getProperty(key, "0")) + 1));
            store(data);
        }

        ChatModel model() {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    try {
                        List<ChatMessage> history = request.messages();
                        if (history.size() == 1 && history.getFirst() instanceof UserMessage) {
                            count("proposalCalls");
                            List<ToolExecutionRequest> calls = new ArrayList<>();
                            for (int i = 0; i < LoopPoc.TOOLS.size(); i++) {
                                calls.add(ToolExecutionRequest.builder().id("batch-1-call-" + (i + 1))
                                        .name(LoopPoc.TOOLS.get(i).name()).arguments("{}").build());
                            }
                            System.out.println("MODEL proposal -> " + calls);
                            return ChatResponse.builder().aiMessage(AiMessage.from(calls)).build();
                        }
                        // Fail visibly if the resumed conversation lost or mismatched a tool result.
                        if (history.size() != 5 || !(history.get(1) instanceof AiMessage assistant)) {
                            throw new IllegalStateException("Expected user + original assistant batch + three results");
                        }
                        List<ToolExecutionRequest> calls = assistant.toolExecutionRequests();
                        if (calls.size() != 3)
                            throw new IllegalStateException("Original batch lost");
                        for (int i = 0; i < calls.size(); i++) {
                            if (!(history.get(i + 2) instanceof ToolExecutionResultMessage result)
                                    || !calls.get(i).id().equals(result.id())
                                    || !calls.get(i).name().equals(result.toolName())) {
                                throw new IllegalStateException("Unpaired tool result at " + i);
                            }
                            System.out.println("MODEL receives " + calls.get(i).id() + " -> " + result.text());
                        }
                        count("finalCalls");
                        return ChatResponse.builder().aiMessage(AiMessage.from("All three tool results received"))
                                .build();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
        }

        Properties beforeApproval() throws IOException {
            Properties workspace = new Properties();
            workspace.setProperty("version", "1");
            workspace.setProperty("content", "original");
            workspace.setProperty("writes", "0");
            store(workspace);
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Apply the fixed patch and run its dependent test"));
            AiMessage response = model().chat(ChatRequest.builder().messages(messages)
                    .toolSpecifications(LoopPoc.TOOLS).build()).aiMessage();
            messages.add(response);
            count("readRuns");
            Properties current = data();
            String read = "v" + current.getProperty("version") + ":" + current.getProperty("content");
            messages.add(ToolExecutionResultMessage.from(response.toolExecutionRequests().getFirst(), read));
            Properties state = new Properties();
            state.setProperty("messages", ChatMessageSerializer.messagesToJson(messages));
            state.setProperty("cursor", "1");
            state.setProperty("base", current.getProperty("version"));
            state.setProperty("phase", "WAITING");
            System.out.println("TOOL call-1 view_file -> " + read + "; cursor=1 (apply_patch), next test not run");
            return state;
        }

        String afterApproval(Properties state, String decision) throws IOException {
            List<ChatMessage> messages = new ArrayList<>(
                    ChatMessageDeserializer.messagesFromJson(state.getProperty("messages")));
            List<ToolExecutionRequest> calls = ((AiMessage) messages.get(1)).toolExecutionRequests();
            int cursor = Integer.parseInt(state.getProperty("cursor"));
            if (cursor != 1 || messages.size() != 3)
                throw new IllegalStateException("Wrong approval checkpoint");
            System.out.println("RESTORED cursor=" + cursor + " savedRead=" + messages.get(2));
            String patchResult = finish(Integer.parseInt(state.getProperty("base")), decision);
            while (cursor < calls.size()) {
                ToolExecutionRequest call = calls.get(cursor);
                String result;
                if (call.name().equals("apply_patch")) {
                    result = patchResult;
                } else if (call.name().equals("run_tests")) {
                    if (patchResult.equals("APPLIED")) {
                        count("testRuns");
                        result = "SIMULATED_PASS v" + data().getProperty("version");
                    } else {
                        result = "SKIPPED: dependent patch " + patchResult;
                    }
                } else
                    throw new IllegalStateException("Unexpected remaining tool " + call.name());
                messages.add(ToolExecutionResultMessage.from(call, result));
                System.out.println("TOOL " + call.id() + " -> " + result);
                cursor++;
            }
            String answer = model().chat(ChatRequest.builder().messages(messages)
                    .toolSpecifications(LoopPoc.TOOLS).build()).aiMessage().text();
            state.setProperty("messages", ChatMessageSerializer.messagesToJson(messages));
            state.setProperty("cursor", Integer.toString(cursor));
            state.setProperty("phase", "DONE");
            state.setProperty("answer", answer);
            // Audit-only completed transcript; not an execution checkpoint for intermediate
            // tools.
            RecoveryPoc.write(directory.resolve("completed-messages.json"), state.getProperty("messages"));
            return answer;
        }
    }

    static void halt(Batch batch) throws IOException {
        batch.print();
        System.out.println("HALT after approval checkpoint pid=" + ProcessHandle.current().pid());
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    static void manual(String command, Path directory, Batch batch, String decision) throws IOException {
        Path file = directory.resolve("manual-batch.properties");
        if (command.equals("start")) {
            RecoveryPoc.save(file, batch.beforeApproval());
            halt(batch);
        }
        Properties state = RecoveryPoc.load(file);
        if (state.isEmpty())
            throw new IllegalStateException("Missing batch checkpoint");
        if (state.getProperty("phase").equals("DONE")) {
            System.out.println("ALREADY FINISHED; no model or tool replay");
        } else {
            System.out.println("FINAL " + batch.afterApproval(state, decision));
            RecoveryPoc.save(file, state);
        }
        batch.print();
    }

    static void agentic(String command, Path directory, Batch batch, String decision) throws IOException {
        AgenticScopePersister.setStore(new RecoveryPoc.FileScopeStore(directory.resolve("scopes")));
        var before = AgenticServices.agentAction(scope -> {
            Properties state = batch.beforeApproval();
            for (String key : state.stringPropertyNames())
                scope.writeState(key, state.getProperty(key));
        });
        var approval = AgenticServices.humanInTheLoopBuilder().description("Approve current tool batch patch")
                .outputKey("approval").responseProvider(scope -> new SuspendedResponse<String>(RecoveryPoc.APPROVAL))
                .build();
        var after = AgenticServices.agentAction(scope -> {
            Properties state = new Properties();
            for (String key : List.of("messages", "cursor", "base", "phase")) {
                state.setProperty(key, scope.readState(key, ""));
            }
            String answer = batch.afterApproval(state, scope.readState("approval", "reject"));
            for (String key : state.stringPropertyNames())
                scope.writeState(key, state.getProperty(key));
            scope.writeState("result", answer);
        });
        Workflow workflow = AgenticServices.sequenceBuilder(Workflow.class)
                .subAgents(before, approval, after).outputKey("result").build();
        if (command.equals("resume")) {
            var scope = workflow.getAgenticScope(RecoveryPoc.TASK);
            if (scope == null)
                throw new IllegalStateException("Missing scope");
            if (scope.readState("phase", "").equals("DONE")) {
                System.out.println("ALREADY FINISHED; no model or tool replay");
                batch.print();
                return;
            }
            if (!scope.pendingResponseIds().contains(RecoveryPoc.APPROVAL)) {
                throw new IllegalStateException("Uncertain state: no pending approval and not DONE");
            }
            scope.completePendingResponse(RecoveryPoc.APPROVAL, decision);
        }
        try {
            System.out.println("FINAL " + workflow.run(RecoveryPoc.TASK));
        } catch (AgenticSystemSuspendedException e) {
            System.out.println("SUSPENDED framework pending=" + e.scope().pendingResponseIds());
            halt(batch);
        }
        batch.print();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3)
            throw new IllegalArgumentException("manual|agentic start|resume|edit scratch [approve|reject]");
        String implementation = args[0];
        String command = args[1];
        Path directory = Path.of(args[2]);
        String decision = args.length > 3 ? args[3] : "approve";
        if (!Set.of("manual", "agentic").contains(implementation)
                || !Set.of("start", "resume", "edit").contains(command)
                || !Set.of("approve", "reject").contains(decision))
            throw new IllegalArgumentException("Invalid arguments");
        System.out
                .println("BATCH PROTOTYPE pid=" + ProcessHandle.current().pid() + " " + implementation + " " + command);
        Path marker = directory.resolve("PROTOTYPE-batch-wipe-me.txt");
        if (command.equals("start")) {
            if (Files.exists(directory))
                throw new IllegalStateException("Use a fresh scratch directory");
            RecoveryPoc.write(marker, "Throwaway single-batch recovery experiment\n");
        } else if (!Files.exists(marker))
            throw new IllegalStateException("Not a batch scratch directory");
        Batch batch = new Batch(directory);
        if (command.equals("edit"))
            batch.edit();
        else if (implementation.equals("manual"))
            manual(command, directory, batch, decision);
        else
            agentic(command, directory, batch, decision);
    }
}
