package ai.codecraft.testfixture;

import ai.codecraft.ServerApplication;
import ai.codecraft.modules.copilot.service.CopilotProposalStore;
import ai.codecraft.modules.copilot.service.CopilotAutoApproval;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.gateway.BoundedModelHttpClientBuilder;
import ai.codecraft.modules.modelaccess.gateway.ModelSdkClientFactory;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.service.ModelConnectionValidator;
import ai.codecraft.modules.workspace.config.WorkspaceProperties;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.FinalTaskAuthorityEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.TestTaskSnapshot;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import io.grpc.Status;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Disposable Linux child JVM. Only the SDK publisher is synthetic; all domain services and RPCs are real. */
public final class CopilotRecoveryApplication {
    public static final String BASE = "https://sdk-fixture.invalid/v1";
    public static final String SOURCE = "src/main/java/interview/CartTotal.java";
    private static String mode;
    private static Path evidence;
    private static final AtomicInteger calls = new AtomicInteger();
    private CopilotRecoveryApplication() { }

    public static void main(String[] args) {
        if (!"disposable".equals(System.getenv("COPILOT_RECOVERY_IT_RUN"))) throw new IllegalStateException("Disposable recovery fixture required");
        mode = args[0]; evidence = Path.of(args[1]);
        new SpringApplication(ServerApplication.class, Faults.class).run("--server.port=" + args[2], "--server.address=127.0.0.1",
            "--identity.public-origin=http://localhost:5173");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Faults {
        @Bean @Primary
        ModelSdkClientFactory controlledSdk(ModelConnectionValidator validator, ModelCallProperties properties) {
            return new ModelSdkClientFactory(validator, properties) {
                @Override public StreamingChatModel create(ModelInvocationDTO target, ModelInvocationLimitsDTO limits, BoundedModelHttpClientBuilder transport) {
                    if (!BASE.equals(target.connection().baseUrl())) throw new IllegalStateException("Recovery fixture forbids real providers");
                    return new StreamingChatModel() {
                        @Override public Flow.Publisher<ChatModelStreamingEvent> chat(ChatRequest request) {
                            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                                private boolean requested;
                                @Override public void request(long demand) {
                                    if (requested) return;
                                    requested = true;
                                    try {
                                        if (calls.incrementAndGet() > 24) throw new IllegalStateException("Fixture call budget exceeded");
                                        String goal = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).toList().getLast().singleText();
                                        if (goal.startsWith("fixture-")) {
                                            append("model-calls.txt", goal + "\n");
                                            verifyPairs(request);
                                        }
                                        if (goal.equals("fixture-stream")) {
                                            if (Files.exists(evidence.resolve("stream-issued.txt"))) throw new IllegalStateException("Unknown model call was replayed");
                                            persist("stream-issued.txt", "issued\n");
                                            subscriber.onNext(new PartialResponse("Incomplete fixture preview"));
                                            return;
                                        }
                                        subscriber.onNext(new CompleteResponse(ChatResponse.builder().aiMessage(answer(request, goal)).build()));
                                        subscriber.onComplete();
                                    } catch (Exception failure) { subscriber.onError(failure); }
                                }
                                @Override public void cancel() { }
                            });
                        }
                    };
                }
            };
        }

        @Bean @Primary
        SandboxWorkspaceGateway faultGateway(WorkspaceProperties properties) {
            return new SandboxWorkspaceGateway(properties) {
                @Override public MutationReceipt apply(ApplyBatchRequest request) {
                    append("applies.txt", request.getOperationId() + "\n");
                    for (String point : List.of("write-before", "write-after")) {
                        Path marker = evidence.resolve(point + ".txt");
                        try {
                            if (Files.exists(marker) && Files.readAllLines(marker).getFirst().equals(request.getOperationId())) {
                                persist("write-replayed.txt", request.getOperationId()); Runtime.getRuntime().halt(138);
                            }
                        } catch (IOException failure) { throw new IllegalStateException(failure); }
                    }
                    MutationReceipt result = super.apply(request);
                    if (mode.equals("write-before")) crash("write-before", result.getOperationId(), result.getRevision());
                    return result;
                }
                @Override public MutationReceipt mutation(String instance, String request) {
                    if (mode.equals("write-before") || mode.equals("recover-blocked") && !Files.exists(evidence.resolve("release-write.txt"))) {
                        throw Status.UNAVAILABLE.withDescription("controlled receipt barrier").asRuntimeException();
                    }
                    return super.mutation(instance, request);
                }
                @Override public TestTaskSnapshot startTest(TestTaskEntity task, FinalTaskAuthorityEntity authority) {
                    append("starts.txt", task.id() + "\n");
                    if (mode.equals("test-recover")) { persist("test-replayed.txt", task.id()); Runtime.getRuntime().halt(138); }
                    TestTaskSnapshot result = super.startTest(task, authority);
                    if (mode.equals("test-crash")) {
                        persist("test-crash.txt", result.getTaskId() + "\n" + result.getExecutionInstanceId() + "\n");
                        Runtime.getRuntime().halt(137);
                    }
                    return result;
                }
                @Override public TestTaskSnapshot task(TestTaskEntity task) {
                    if (mode.equals("test-crash") || mode.equals("test-recover") && !Files.exists(evidence.resolve("release-test.txt"))) {
                        throw Status.UNAVAILABLE.withDescription("controlled task barrier").asRuntimeException();
                    }
                    return super.task(task);
                }
            };
        }

        @Bean static BeanPostProcessor committedReceiptCrash() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    boolean automatic = bean instanceof CopilotAutoApproval;
                    if (!automatic && (!mode.equals("write-after") || !(bean instanceof WorkspaceCoordinationStore || bean instanceof CopilotProposalStore))) return bean;
                    var proxy = new ProxyFactory(bean); proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        if (automatic && invocation.getMethod().getName().equals("apply") && Files.exists(evidence.resolve("block-auto.txt"))
                            && !Files.exists(evidence.resolve("release-auto.txt"))) return null;
                        if (bean instanceof CopilotProposalStore && invocation.getMethod().getName().equals("recoverTask")) return null;
                        Object result = invocation.proceed();
                        if (bean instanceof WorkspaceCoordinationStore && invocation.getMethod().getName().equals("result")
                            && invocation.getArguments()[1] instanceof MutationReceipt receipt) {
                            if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Crash must follow transaction commit");
                            var operation = (WorkspaceOperationEntity) invocation.getArguments()[0];
                            crash("write-after", operation.id(), receipt.getRevision());
                        }
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
    }

    private static AiMessage answer(ChatRequest request, String goal) {
        if (!goal.startsWith("fixture-")) {
            if (request.messages().getLast() instanceof ToolExecutionResultMessage result) return AiMessage.from(result.text());
            if (goal.startsWith("This is a connection check.")) return AiMessage.from(goal.substring(goal.indexOf('\n') + 1).trim());
            int start = goal.indexOf("nonce \"") + 7;
            if (start < 7) throw new IllegalStateException("Unknown probe");
            String nonce = goal.substring(start, goal.indexOf('"', start));
            return AiMessage.from(tool("probe", "connection_probe", "{\"nonce\":\"" + nonce + "\"}"));
        }
        int lastUser = request.messages().size() - 1;
        while (!(request.messages().get(lastUser) instanceof UserMessage)) lastUser--;
        if (lastUser < request.messages().size() - 1 || goal.equals("fixture-finish")) return AiMessage.from("Recovered original results; no repeated operations.");
        String[] parts = goal.split(" ");
        if (parts[0].equals("fixture-proposal") && parts.length == 3 && parts[1].matches("[0-9]+") && parts[2].matches("[a-z]+")) {
            String path = "src/main/java/exercise/Recovery_" + parts[2] + ".java";
            String proposal = "{\"expected_revision\":\"" + parts[1] + "\",\"changes\":[{\"kind\":\"CREATE\",\"path\":\"" + path
                + "\",\"content\":\"package exercise; final class Recovery_" + parts[2] + " {}\"}]}";
            return AiMessage.builder().thinking("fixture-reasoning-retained").toolExecutionRequests(List.of(
                tool("before", "view_file", "{\"path\":\"" + SOURCE + "\"}"), tool("proposal", "propose_changes", proposal),
                tool("after", "view_file", "{\"path\":\"" + path + "\"}"))).build();
        }
        if (parts[0].equals("fixture-test") && parts.length == 2 && parts[1].matches("[0-9]+")) {
            return AiMessage.builder().thinking("fixture-reasoning-retained").toolExecutionRequests(List.of(
                tool("test", "run_tests", "{\"expected_revision\":\"" + parts[1] + "\",\"target\":\"base\"}"))).build();
        }
        throw new IllegalStateException("Unknown fixture direction");
    }

    private static ToolExecutionRequest tool(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }
    private static void verifyPairs(ChatRequest request) {
        var pending = new ArrayDeque<String>();
        for (var message : request.messages()) {
            if (message instanceof ToolExecutionResultMessage result) {
                if (pending.isEmpty() || !pending.removeFirst().equals(result.id())) throw new IllegalStateException("Tool pairing lost");
            } else {
                if (!pending.isEmpty()) throw new IllegalStateException("Incomplete batch entered model context");
                if (message instanceof AiMessage assistant && assistant.hasToolExecutionRequests()) {
                    if (!"fixture-reasoning-retained".equals(assistant.thinking())) throw new IllegalStateException("Opaque reasoning lost");
                    assistant.toolExecutionRequests().forEach(tool -> pending.addLast(tool.id()));
                }
            }
        }
        if (!pending.isEmpty()) throw new IllegalStateException("Missing tool results");
    }
    private static void crash(String point, String operation, long revision) {
        persist(point + ".txt", operation + "\n" + revision + "\n"); Runtime.getRuntime().halt(137);
    }
    private static void persist(String name, String value) { write(name, value, StandardOpenOption.CREATE_NEW); }
    private static synchronized void append(String name, String value) { write(name, value, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
    private static void write(String name, String value, StandardOpenOption... options) {
        try {
            var flags = new HashSet<StandardOpenOption>(List.of(options)); flags.add(StandardOpenOption.WRITE);
            try (FileChannel file = FileChannel.open(evidence.resolve(name), flags)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
                while (bytes.hasRemaining()) file.write(bytes); file.force(true);
            }
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist owned fixture evidence", failure); }
    }
}
