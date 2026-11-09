package ai.codecraft;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codecraft.identity.IdentityBootstrap;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ai.codecraft.status.v1.CheckRequest;
import ai.codecraft.status.v1.CheckResponse;
import ai.codecraft.status.v1.SandboxStatusGrpc;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.enabled=false",
    "identity.fingerprint-key=0000000000000000000000000000000000000000000000000000000000000000",
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1&socketTimeout=2",
    "spring.datasource.username=test",
    "spring.datasource.password=test-only",
    "spring.datasource.hikari.initialization-fail-timeout=-1"
})
class StatusHttpTest {
    @MockitoBean
    private IdentityBootstrap identityBootstrap;
    private static volatile String behavior = "unavailable";
    private static Server fixture;

    @DynamicPropertySource
    static void fixtureProperties(DynamicPropertyRegistry registry) throws IOException {
        // A real gRPC transport with an explicit external-service fault fixture.
        fixture = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(new SandboxStatusGrpc.SandboxStatusImplBase() {
                @Override
                public void check(CheckRequest request, StreamObserver<CheckResponse> response) {
                    switch (behavior) {
                        case "serving", "not-serving":
                            response.onNext(CheckResponse.newBuilder().setServing("serving".equals(behavior)).build());
                            response.onCompleted();
                            break;
                        case "timeout":
                            // Deliberately no response; the Java caller must enforce its deadline.
                            break;
                        default:
                            response.onError(Status.UNAVAILABLE.withDescription("internal-secret-details").asRuntimeException());
                    }
                }
            }).build().start();
        registry.add("tracer.sandbox-port", fixture::getPort);
    }

    @AfterAll
    static void closeFixture() throws InterruptedException {
        if (fixture != null) {
            fixture.shutdownNow();
            fixture.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @LocalServerPort
    private int port;

    @Test
    void unavailableDependenciesAreReportedWithoutLeakingDetails() throws Exception {
        behavior = "unavailable";
        assertStatus("UNAVAILABLE");
    }

    @Test
    void databaseFailureDoesNotHideResponsiveGoService() throws Exception {
        behavior = "serving";
        assertStatus("AVAILABLE");
    }

    @Test
    void nonServingGoIsNotReportedAsAvailable() throws Exception {
        behavior = "not-serving";
        assertStatus("UNAVAILABLE");
    }

    @Test
    void unresponsiveGoIsBoundedByDeadline() throws Exception {
        behavior = "timeout";
        long started = System.nanoTime();
        assertStatus("UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
    }

    private void assertStatus(String sandbox) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/api/internal/status")).timeout(Duration.ofSeconds(7)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().allValues("cache-control")).containsExactly("no-store");
            assertThat(response.body()).isEqualTo("{\"database\":\"UNAVAILABLE\",\"sandbox\":\"" + sandbox + "\"}");
        }
    }
}
