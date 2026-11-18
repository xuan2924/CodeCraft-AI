package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.codecraft.common.exception.GlobalExceptionHandler;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.controller.AdminProblemController;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.Configuration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfEnvironmentVariable(named = "COPILOT_IT_JDBC_URL", matches = ".+")
class PublicationConcurrencyTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private DataSourceTransactionManager transactions;

    private <T> T proxy(T service, Class<T> type) {
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    private byte[] pythonArtifact(String id) throws Exception {
        var pkg = temporary.resolve(id);
        for (String name : new String[]{"initial/src/main.py", "reference/src/main.py", "initial/pyproject.toml", "acceptance/test_main.py", "README.md"}) {
            var path = pkg.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, "# static fixture\n");
        }
        var root = Path.of("../..").toAbsolutePath().normalize();
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(root.resolve("problems/cart-total/1.1.0/manifest.json").toFile());
        manifest.remove("execution"); manifest.remove("challenge"); manifest.put("id", id); manifest.put("language", "PYTHON_PYTEST");
        manifest.putArray("editableRoots").add("src").add("tests");
        Files.writeString(pkg.resolve("manifest.json"), manifest.toString());
        var output = temporary.resolve(id + ".json");
        var process = new ProcessBuilder("node", "scripts/publish-problem.mjs", pkg.toString(), temporary.resolve("receipt").toString(), output.toString())
            .directory(root.toFile()).inheritIO().start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero();
        return Files.readAllBytes(output);
    }

    private MockHttpServletResponse call(String route, String key, byte[] bytes) throws Exception {
        return mvc.perform(post("/api/admin/problems/" + route).contentType("application/json").header("Idempotency-Key", key).content(bytes)).andReturn().getResponse();
    }
    private MockHttpServletResponse[] race(Callable<MockHttpServletResponse> first, Callable<MockHttpServletResponse> second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return second.call(); });
            return new MockHttpServletResponse[]{a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)};
        }
    }

    @Test void snapshotPublicationIsStaticAndConcurrentReceiptsAreAtomic() throws Exception {
        var bytes = pythonArtifact("python-static");
        String url = System.getenv("COPILOT_IT_JDBC_URL");
        String database = "publish_" + UUID.randomUUID().toString().replace("-", "");
        int end = url.indexOf('?') < 0 ? url.length() : url.indexOf('?');
        int slash = url.lastIndexOf('/', end - 1);
        var bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, "fixture", "fixture"));
        var source = new DriverManagerDataSource(url.substring(0, slash + 1) + database + url.substring(end), "fixture", "fixture");
        var jdbc = new JdbcTemplate(source); transactions = new DataSourceTransactionManager(source);
        bootstrap.execute("CREATE DATABASE " + database);
        try {
            Flyway.configure().dataSource(source).load().migrate();
            var config = new Configuration(); config.addMapper(ProblemRepository.class);
            var factory = new SqlSessionFactoryBean(); factory.setConfiguration(config); factory.setDataSource(source);
            var repo = new SqlSessionTemplate(factory.getObject()).getMapper(ProblemRepository.class);
            var versions = proxy(new ProblemTransactionService(repo), ProblemTransactionService.class);
            var identity = mock(IdentityTransactionService.class);
            var auth = new SessionAuthorizationDTO(UUID.randomUUID().toString(), "ADMIN", false, 1, Instant.MAX);
            when(identity.authorization(any())).thenReturn(auth); when(identity.authorizeMutation(any())).thenReturn(auth);
            var controller = new AdminProblemController(proxy(new AdminProblemStore(identity, repo, versions), AdminProblemStore.class),
                new ProblemService(versions, new ProblemPublicationReader(json)));
            mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(json)).build();
            var validation = call("validate", "unused", bytes);
            assertThat(validation.getStatus()).isEqualTo(200);
            assertThat(json.readTree(validation.getContentAsByteArray()).at("/data/executionSupported").booleanValue()).isFalse();
            var same = race(() -> call("publish", "same", bytes), () -> call("publish", "same", bytes));
            assertThat(same).allMatch(r -> r.getStatus() == 200);
            String id = json.readTree(same[0].getContentAsByteArray()).at("/data/id").asText();
            assertThat(json.readTree(same[1].getContentAsByteArray()).at("/data/id").asText()).isEqualTo(id);
            var down = "{\"available\":false}".getBytes();
            var up = "{\"available\":true}".getBytes();
            String route = id + "/availability";
            assertThat(call(route, "down", down).getStatus()).isEqualTo(200);
            assertThat(call(route, "up", up).getStatus()).isEqualTo(200);
            assertThat(call(route, "down", down).getStatus()).isEqualTo(200);
            assertThat(call(route, "down", up).getStatus()).isEqualTo(409);
            assertThatThrownBy(() -> versions.requireAvailable(id)).hasMessage("EXECUTION_UNSUPPORTED");
            assertThat(call("publish", "same", "{}".getBytes()).getStatus()).isEqualTo(409);
            // Two valid artifact variants compete for a fresh version, not an already established winner.
            byte[] a = pythonArtifact("python-race");
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(a);
            tree.put("annotation", "different");
            var results = race(() -> call("publish", "left", a), () -> call("publish", "right", json.writeValueAsBytes(tree)));
            assertThat(results).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200,409);
            String loser = results[0].getStatus() == 409 ? "left" : "right";
            assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_publish_receipt WHERE request_key=?", Integer.class, loser)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM problem_version WHERE problem_id='python-race'", Integer.class)).isOne();
            assertThat(call("publish", loser, results[0].getStatus() == 200 ? a : json.writeValueAsBytes(tree)).getStatus()).isEqualTo(200);
        } finally { bootstrap.execute("DROP DATABASE " + database); }
    }
}
