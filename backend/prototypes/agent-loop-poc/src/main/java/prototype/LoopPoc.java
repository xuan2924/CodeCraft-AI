package prototype;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** THROWAWAY: real LC4j loop, scripted model, in-memory single-user workspace. */
public class LoopPoc {
    static final List<ToolSpecification> TOOLS = List.of(
            spec("view_file"), spec("apply_patch"), spec("run_tests"));

    public interface Assistant {
        String chat(String message);
    }

    enum Scenario { AUTO, APPROVE, REJECT, CONFLICT, DOUBLE_APPROVE }

    static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description("PROTOTYPE fixed fixture: " + name).build();
    }

    // No provider or network. The SAME response batch is used by both implementations.
    static class ScriptedModel implements ChatModel {
        volatile int calls;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            calls++;
            System.out.println("  MODEL call=" + calls + " messages=" + request.messages().size());
            if (calls == 1) {
                List<ToolExecutionRequest> batch = TOOLS.stream()
                        .map(t -> ToolExecutionRequest.builder().id("call-" + t.name())
                                .name(t.name()).arguments("{}").build()).toList();
                return ChatResponse.builder().aiMessage(AiMessage.from(batch)).build();
            }
            if (calls > 2) {
                throw new IllegalStateException("Scripted model exhausted: unexpected extra invocation");
            }
            List<String> results = request.messages().stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .map(r -> r.id() + "=" + r.text()).toList();
            return ChatResponse.builder().aiMessage(AiMessage.from("received=" + results)).build();
        }
    }

    // Shared BUSINESS policy, not a feature provided by either loop.
    // No real file paths, gRPC, shell, authentication or persistent state in this experiment.
    static class Workspace {
        int version = 1;
        int base;
        int writes;
        int testRuns;
        String content = "original";
        String patchState = "NONE";
        String outcome;

        synchronized void prepare() {
            base = version;
            patchState = "PENDING";
        }

        synchronized void userEdit() {
            version++;
            content = "user-edit";
            System.out.println("  USER edit -> v" + version);
        }

        synchronized String resolve(boolean approve) {
            if (!patchState.equals("PENDING")) {
                System.out.println("  DECISION duplicate ignored, result=" + outcome);
                return outcome;
            }
            if (!approve) {
                patchState = "REJECTED";
            } else if (base != version) {
                patchState = "CONFLICT";
            } else {
                patchState = "APPLIED";
                writes++;
                version++;
                content = "ai-edit";
            }
            outcome = patchState + " workspace=v" + version;
            System.out.println("  DECISION " + outcome);
            return outcome;
        }

        synchronized String execute(String name) {
            return switch (name) {
                case "view_file" -> "v" + version + ":" + content;
                case "apply_patch" -> {
                    if (outcome == null) throw new IllegalStateException("Unresolved approval");
                    yield outcome;
                }
                case "run_tests" -> {
                    // This batch's test depends on the proposed patch, not a global ban on read-only tests.
                    if (!patchState.equals("APPLIED")) yield "SKIPPED: dependent patch not applied";
                    testRuns++;
                    yield "SIMULATED_PASS v" + version;
                }
                default -> throw new IllegalArgumentException("Unknown tool " + name);
            };
        }

        synchronized String snapshot(ScriptedModel model) {
            return "{version=" + version + ", content=" + content + ", patch=" + patchState
                    + ", base=" + base + ", writes=" + writes + ", testRuns=" + testRuns
                    + ", modelCalls=" + model.calls + "}";
        }
    }

    static void decide(Scenario scenario, Workspace workspace) {
        if (scenario == Scenario.CONFLICT) workspace.userEdit();
        workspace.resolve(scenario != Scenario.REJECT);
        if (scenario == Scenario.DOUBLE_APPROVE) workspace.resolve(true);
    }

    // Framework owns the loop. This deliberately simple adapter BLOCKS a virtual thread
    // until approval; it is NOT a durable suspension API or a claim about all AI Services designs.
    static void automatic(Scenario scenario) throws Exception {
        Workspace workspace = new Workspace();
        ScriptedModel model = new ScriptedModel();
        CompletableFuture<Void> pending = new CompletableFuture<>();
        CompletableFuture<Void> approved = new CompletableFuture<>();
        Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
        for (ToolSpecification tool : TOOLS) {
            executors.put(tool, (request, memoryId) -> {
                if (request.name().equals("apply_patch")) {
                    workspace.prepare();
                    if (scenario == Scenario.AUTO) {
                        workspace.resolve(true);
                    } else {
                        pending.complete(null);
                        try {
                            approved.get(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Approval interrupted", e);
                        } catch (Exception e) {
                            throw new IllegalStateException("Approval not resolved", e);
                        }
                    }
                }
                String value = workspace.execute(request.name());
                System.out.println("  TOOL " + request.name() + " -> " + value);
                return value;
            });
        }
        Assistant assistant = AiServices.builder(Assistant.class).chatModel(model)
                .tools(executors).maxToolCallingRoundTrips(3).build();
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var invocation = worker.submit(() -> assistant.chat("Apply the fixed fixture patch then test."));
            if (scenario != Scenario.AUTO) {
                pending.get(10, TimeUnit.SECONDS);
                System.out.println("  WAIT invocationDone=" + invocation.isDone() + " " + workspace.snapshot(model));
                decide(scenario, workspace);
                approved.complete(null);
            }
            System.out.println("  FINAL " + invocation.get(10, TimeUnit.SECONDS));
            System.out.println("  STATE " + workspace.snapshot(model));
        }
    }

    // Application owns the loop and cursor. runUntilPause RETURNS instead of waiting.
    // These fields are in memory only; making them durable is separate work.
    static class ManualLoop {
        final Workspace workspace = new Workspace();
        final ScriptedModel model = new ScriptedModel();
        final List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("Apply the fixed fixture patch then test.")));
        List<ToolExecutionRequest> batch = List.of();
        int cursor;
        boolean waiting;
        boolean done;

        void runUntilPause(boolean auto) {
            if (done) return;
            if (waiting) {
                if (workspace.outcome == null) return;
                waiting = false;
            }
            while (true) {
                while (cursor < batch.size()) {
                    ToolExecutionRequest tool = batch.get(cursor);
                    if (tool.name().equals("apply_patch") && workspace.patchState.equals("NONE")) {
                        workspace.prepare();
                        if (!auto) {
                            waiting = true;
                            return;
                        }
                        workspace.resolve(true);
                    }
                    String value = workspace.execute(tool.name());
                    System.out.println("  TOOL " + tool.name() + " -> " + value);
                    messages.add(ToolExecutionResultMessage.from(tool, value));
                    cursor++;
                }
                if (model.calls >= 3) throw new IllegalStateException("Model call budget exhausted");
                AiMessage reply = model.chat(ChatRequest.builder().messages(messages).toolSpecifications(TOOLS).build())
                        .aiMessage();
                messages.add(reply);
                if (!reply.hasToolExecutionRequests()) {
                    done = true;
                    System.out.println("  FINAL " + reply.text());
                    return;
                }
                batch = reply.toolExecutionRequests();
                cursor = 0;
            }
        }
    }

    static void manual(Scenario scenario) {
        ManualLoop loop = new ManualLoop();
        loop.runUntilPause(scenario == Scenario.AUTO);
        if (loop.waiting) {
            System.out.println("  WAIT runUntilPause returned; cursor=" + loop.cursor + " " + loop.workspace.snapshot(loop.model));
            decide(scenario, loop.workspace);
            loop.runUntilPause(false);
        }
        System.out.println("  STATE " + loop.workspace.snapshot(loop.model));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("THROWAWAY / LangChain4j 1.23.0 / scripted model / no API key / no persistence");
        for (Scenario scenario : Scenario.values()) {
            System.out.println("\n=== AI_SERVICES / " + scenario + " ===");
            automatic(scenario);
            System.out.println("\n=== MANUAL / " + scenario + " ===");
            manual(scenario);
        }
    }
}
