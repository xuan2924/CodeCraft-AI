package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import ai.codecraft.modules.modelaccess.repository.ProxyAuditRepository;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Repeats the HTTP relay suite against real PostgreSQL/MyBatis audit storage. */
@EnabledIfEnvironmentVariable(named = "MODEL_ACCESS_IT_JDBC_URL", matches = ".+")
class LlmProxyPersistenceIT extends LlmProxyTest {
    private static SqlSessionTemplate sql;
    private static DriverManagerDataSource source;
    @BeforeAll static void database() throws Exception {
        source = new DriverManagerDataSource(System.getenv("MODEL_ACCESS_IT_JDBC_URL"), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ProxyAuditRepository.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject());
    }
    @Override protected ProxyAuditRepository createRepository() {
        return mock(ProxyAuditRepository.class, delegatesTo(sql.getMapper(ProxyAuditRepository.class)));
    }
    @Test void terminalPrivateDeliveryCanAcquireItsRequiredPostgresRowLock() {
        String id = java.util.UUID.randomUUID().toString();
        repository.start(id, id, java.time.Instant.now(), "messages", 2, "{}");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        var identity = mock(ai.codecraft.modules.identity.service.IdentityTransactionService.class);
        org.mockito.Mockito.when(identity.authorizePrivateDelivery("session")).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id::text FROM model_proxy_call WHERE id=?::uuid FOR UPDATE", String.class, id);
            return new ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO(id, "USER", false, 1, java.time.Instant.MAX);
        });
        var exercises = mock(ai.codecraft.modules.workspace.repository.ExerciseRepository.class);
        var exercise = mock(ai.codecraft.modules.workspace.model.entity.ExerciseEntity.class);
        org.mockito.Mockito.when(exercise.userId()).thenReturn(id);
        org.mockito.Mockito.when(exercises.find(id)).thenReturn(exercise);
        var store = new ai.codecraft.modules.workspace.service.TerminalStore(mock(ai.codecraft.modules.workspace.service.CommandTaskStore.class),
            mock(ai.codecraft.modules.workspace.repository.TerminalRepository.class), identity, mock(ai.codecraft.modules.identity.service.IdentitySecrets.class),
            mock(ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository.class), exercises, java.time.Clock.systemUTC());
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        manager.setEnforceReadOnly(true);
        var factory = new org.springframework.aop.framework.ProxyFactory(store);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxied = (ai.codecraft.modules.workspace.service.TerminalStore) factory.getProxy();
        assertThat(proxied.history("session", id)).isEmpty();
        repository.delete(id);
    }
    @Test void persistedTraceAndTotalsAreReadableAndEraseWithExercise() throws Exception {
        String exercise = "10000000-0000-0000-0000-000000000001";
        repository.delete(exercise);
        protocolsReplaceKeysStreamAndAccount(ModelProtocol.ANTHROPIC, false);
        assertThat(repository.events(exercise)).hasSize(1);
        assertThat(repository.events(exercise).getFirst().toolSignals()).isEqualTo(1);
        assertThat(repository.events(exercise).getFirst().state()).isEqualTo("SUCCEEDED");
        assertThat(repository.totals(exercise).getFirst().inputTokens()).isEqualTo("15");
        assertThat(repository.totals(exercise).getFirst().calls()).isEqualTo("1");
        assertThat(repository.bytes(exercise)).isPositive();
        assertThat(repository.quiescent(exercise)).isTrue();
        repository.delete(exercise);
        assertThat(repository.events(exercise)).isEmpty();
    }
}
