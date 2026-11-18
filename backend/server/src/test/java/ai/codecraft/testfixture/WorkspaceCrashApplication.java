package ai.codecraft.testfixture;

import ai.codecraft.ServerApplication;
import ai.codecraft.fixture.CaptchaFixture;
import ai.codecraft.modules.workspace.gateway.SandboxClients;
import ai.codecraft.modules.workspace.service.SandboxRoutingStore;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.TestTaskSnapshot;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import io.grpc.Status;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only child process: real RPC, hard termination before returning its receipt to the coordinator. */
public final class WorkspaceCrashApplication {
    private WorkspaceCrashApplication() { }
    private static boolean crash;
    private static String mode;
    private static Path evidence;

    public static void main(String[] args) {
        mode = args[0];
        crash = mode.equals("crash");
        evidence = Path.of(args[1]);
        SpringApplication application = new SpringApplication(ServerApplication.class, FaultConfiguration.class, CaptchaFixture.class);
        application.run("--server.port=" + args[2], "--server.address=127.0.0.1",
            "--identity.public-origin=http://localhost:5173");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean
        @Primary
        SandboxWorkspaceGateway crashGateway(SandboxClients clients, SandboxRoutingStore routes) {
            return new SandboxWorkspaceGateway(clients, routes) {
                @Override
                public MutationReceipt apply(ApplyBatchRequest request) {
                    if (!crash) {
                        persist("REPLAYED\n");
                        Runtime.getRuntime().halt(138);
                    }
                    MutationReceipt receipt = super.apply(request);
                    persist(receipt.getOperationId() + "\n" + receipt.getRevision() + "\n");
                    Runtime.getRuntime().halt(137);
                    throw new IllegalStateException("halt returned");
                }

                @Override
                public MutationReceipt mutation(String instance, String request) {
                    // A competing reconciliation worker must not save the receipt before this exact crash point.
                    if (crash) throw Status.UNAVAILABLE.withDescription("test crash barrier").asRuntimeException();
                    return super.mutation(instance, request);
                }

                @Override
                public TestTaskSnapshot startTest(TestTaskEntity task, ai.codecraft.modules.workspace.model.entity.FinalTaskAuthorityEntity authority) {
                    if (mode.equals("task-recover")) {
                        persist("START_REPLAYED\n");
                        Runtime.getRuntime().halt(138);
                    }
                    TestTaskSnapshot result = super.startTest(task, authority);
                    if (mode.equals("task-crash")) {
                        persist(result.getTaskId() + "\n" + result.getExecutionInstanceId() + "\n");
                        Runtime.getRuntime().halt(137);
                    }
                    return result;
                }

                @Override
                public TestTaskSnapshot task(TestTaskEntity task) {
                    if (mode.equals("task-crash")) throw Status.UNAVAILABLE.withDescription("test execution crash barrier").asRuntimeException();
                    return super.task(task);
                }

                private void persist(String value) {
                    try (FileChannel file = FileChannel.open(evidence, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
                        while (bytes.hasRemaining()) file.write(bytes);
                        file.force(true);
                    } catch (IOException error) {
                        throw new IllegalStateException("Cannot persist test crash evidence", error);
                    }
                }
            };
        }
    }
}
