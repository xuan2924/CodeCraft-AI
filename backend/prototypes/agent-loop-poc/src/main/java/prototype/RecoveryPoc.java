package prototype;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.agentic.scope.AgenticScopeKey;
import dev.langchain4j.agentic.scope.AgenticScopePersister;
import dev.langchain4j.agentic.scope.AgenticScopeSerializer;
import dev.langchain4j.agentic.scope.AgenticScopeStore;
import dev.langchain4j.agentic.scope.AgenticSystemSuspendedException;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.MemoryId;

/**
 * THROWAWAY. Single task per scratch directory, single process at a time. No
 * real provider.
 */
public class RecoveryPoc {
    static final String TASK = "prototype-task";
    static final String APPROVAL = "patch-approval";

    public interface Workflow extends AgenticScopeAccess {
        @Agent
        String run(@MemoryId String task);
    }

    // Application-owned file operations. No DB durability / power-loss guarantees
    // claimed.
    static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, value);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    static Properties load(Path path) throws IOException {
        Properties data = new Properties();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                data.load(reader);
            }
        }
        return data;
    }

    static void save(Path path, Properties data) throws IOException {
        var text = new java.io.StringWriter();
        data.store(text, "PROTOTYPE, wipe me. No credentials.");
        write(path, text.toString());
    }

    static class FileScopeStore implements AgenticScopeStore {
        final Path directory;

        FileScopeStore(Path directory) {
            this.directory = directory;
        }

        Path file(AgenticScopeKey key) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (key.agentId() + "\n" + key.memoryId()).getBytes(StandardCharsets.UTF_8));
            return directory.resolve(encoded + ".json");
        }

        @Override
        public boolean save(AgenticScopeKey key, DefaultAgenticScope scope) {
            try {
                write(file(key), AgenticScopeSerializer.toJson(scope));
                System.out.println("STORE checkpoint " + key);
                return true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Optional<DefaultAgenticScope> load(AgenticScopeKey key) {
            try {
                Path path = file(key);
                return Files.exists(path) ? Optional.of(AgenticScopeSerializer.fromJson(Files.readString(path)))
                        : Optional.empty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean delete(AgenticScopeKey key) {
            try {
                return Files.deleteIfExists(file(key));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Set<AgenticScopeKey> getAllKeys() {
            Set<AgenticScopeKey> keys = new HashSet<>();
            if (!Files.exists(directory))
                return keys;
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                    String name = path.getFileName().toString();
                    String decoded = new String(Base64.getUrlDecoder().decode(name.substring(0, name.length() - 5)),
                            StandardCharsets.UTF_8);
                    String[] parts = decoded.split("\n", 2);
                    keys.add(new AgenticScopeKey(parts[0], parts[1]));
                }
                return keys;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // Shared fixture. The proposal model call is recorded on disk, so restart
    // cannot hide a repeated call.
    static class Fixture {
        final Path directory;

        Fixture(Path directory) {
            this.directory = directory;
        }

        int propose() throws IOException {
            Properties data = load(directory.resolve("workspace.properties"));
            int calls = Integer.parseInt(data.getProperty("proposalCalls", "0")) + 1;
            data.setProperty("proposalCalls", Integer.toString(calls));
            data.putIfAbsent("version", "1");
            data.putIfAbsent("content", "original");
            data.putIfAbsent("writes", "0");
            save(directory.resolve("workspace.properties"), data);
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("fixed-ai-edit")).build();
                }
            };
            String proposal = model.chat("Generate the fixed prototype patch");
            System.out.println("MODEL proposalCalls=" + calls + " proposal=" + proposal);
            return Integer.parseInt(data.getProperty("version"));
        }

        void edit() throws IOException {
            Properties data = load(directory.resolve("workspace.properties"));
            data.setProperty("version", Integer.toString(Integer.parseInt(data.getProperty("version")) + 1));
            data.setProperty("content", "user-edit");
            save(directory.resolve("workspace.properties"), data);
            System.out.println("USER edit " + data);
        }

        String finish(int base, String decision) throws IOException {
            Properties data = load(directory.resolve("workspace.properties"));
            if (data.containsKey("receipt"))
                return data.getProperty("receipt");
            int version = Integer.parseInt(data.getProperty("version"));
            String result;
            if (!decision.equals("approve"))
                result = "REJECTED";
            else if (version != base)
                result = "CONFLICT";
            else {
                result = "APPLIED";
                data.setProperty("version", Integer.toString(version + 1));
                data.setProperty("content", "fixed-ai-edit");
                data.setProperty("writes", Integer.toString(Integer.parseInt(data.getProperty("writes")) + 1));
            }
            // Receipt and effect share one local fixture file; this does NOT model Java/Go
            // distributed writes.
            data.setProperty("receipt", result);
            save(directory.resolve("workspace.properties"), data);
            System.out.println("RESULT " + result);
            return result;
        }

        void print() throws IOException {
            System.out.println("WORKSPACE " + load(directory.resolve("workspace.properties")));
        }
    }

    static void agentic(String command, Path directory, Fixture fixture, String decision) throws IOException {
        AgenticScopePersister.setStore(new FileScopeStore(directory.resolve("scopes")));
        var propose = AgenticServices.agentAction(scope -> scope.writeState("base", fixture.propose()));
        var approval = AgenticServices.humanInTheLoopBuilder().description("Prototype patch approval")
                .outputKey("approval").responseProvider(scope -> new SuspendedResponse<String>(APPROVAL)).build();
        var apply = AgenticServices.agentAction(scope -> scope.writeState("result",
                fixture.finish(scope.readState("base", 0), scope.readState("approval", "reject"))));
        Workflow workflow = AgenticServices.sequenceBuilder(Workflow.class)
                .subAgents(propose, approval, apply).outputKey("result").build();
        if (command.equals("resume")) {
            AgenticScope scope = workflow.getAgenticScope(TASK);
            if (scope == null)
                throw new IllegalStateException("No saved scope; refusing to restart task");
            if (scope.pendingResponseIds().isEmpty()) {
                System.out.println("ALREADY FINISHED / no pending approval; refusing duplicate invocation");
                fixture.print();
                return;
            }
            System.out
                    .println("RESTORED pending=" + scope.pendingResponseIds() + " base=" + scope.readState("base", 0));
            scope.completePendingResponse(APPROVAL, decision);
        }
        try {
            System.out.println("WORKFLOW " + workflow.run(TASK));
        } catch (AgenticSystemSuspendedException e) {
            System.out.println("SUSPENDED pending=" + e.scope().pendingResponseIds());
            fixture.print();
            System.out.println("HALT JVM after framework checkpoint, pid=" + ProcessHandle.current().pid());
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }
        fixture.print();
    }

    static void manual(String command, Path directory, Fixture fixture, String decision) throws IOException {
        Path path = directory.resolve("manual.properties");
        if (command.equals("start")) {
            Properties state = new Properties();
            state.setProperty("base", Integer.toString(fixture.propose()));
            state.setProperty("phase", "WAITING");
            save(path, state);
            fixture.print();
            System.out.println("SUSPENDED application checkpoint; HALT JVM pid=" + ProcessHandle.current().pid());
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }
        Properties state = load(path);
        if (state.isEmpty())
            throw new IllegalStateException("Missing checkpoint");
        if (state.getProperty("phase").equals("DONE")) {
            System.out.println("ALREADY FINISHED; refusing duplicate invocation");
        } else {
            state.setProperty("result", fixture.finish(Integer.parseInt(state.getProperty("base")), decision));
            state.setProperty("phase", "DONE");
            save(path, state);
        }
        System.out.println("MANUAL " + state);
        fixture.print();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3)
            throw new IllegalArgumentException("manual|agentic start|resume|edit scratch-dir [approve|reject]");
        String implementation = args[0];
        String command = args[1];
        Path directory = Path.of(args[2]);
        String decision = args.length > 3 ? args[3] : "approve";
        if (!Set.of("manual", "agentic").contains(implementation)
                || !Set.of("start", "resume", "edit").contains(command)
                || !Set.of("approve", "reject").contains(decision))
            throw new IllegalArgumentException("Invalid arguments");
        System.out.println("PROTOTYPE pid=" + ProcessHandle.current().pid() + " " + implementation + " " + command);
        Path marker = directory.resolve("PROTOTYPE-wipe-me.txt");
        if (command.equals("start")) {
            if (Files.exists(directory))
                throw new IllegalStateException("Use a fresh scratch directory; no overwrite");
            write(marker, "Single-user recovery experiment. No production data.\n");
        } else if (!Files.exists(marker))
            throw new IllegalStateException("Not a prototype scratch directory");
        Fixture fixture = new Fixture(directory);
        if (command.equals("edit"))
            fixture.edit();
        else if (implementation.equals("manual"))
            manual(command, directory, fixture, decision);
        else
            agentic(command, directory, fixture, decision);
    }
}
