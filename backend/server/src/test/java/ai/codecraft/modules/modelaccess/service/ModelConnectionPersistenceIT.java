package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import ai.codecraft.modules.identity.repository.RevocationIntentRepository;
import ai.codecraft.modules.identity.service.IdentityRevocationBarrier;
import ai.codecraft.modules.identity.service.IdentityRevocationNotifier;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.config.ModelSecurityProperties;
import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import ai.codecraft.modules.modelaccess.security.ModelCredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.Configuration;
import java.sql.Timestamp;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable PostgreSQL/MyBatis business seam; never calls a model provider. */
@EnabledIfEnvironmentVariable(named = "MODEL_ACCESS_IT_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelConnectionPersistenceIT {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ModelConnectionRequest input = new ModelConnectionRequest("DeepSeek", "https://api.deepseek.com", ModelProtocol.OPENAI_CHAT,
        ModelProviderPreset.DEEPSEEK, "fixture-not-a-real-model-key", null);
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transactions;
    private ModelConnectionService service;
    private ModelConfigurationService models;
    private String owner;

    @BeforeAll
    void migrate() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("MODEL_ACCESS_IT_JDBC_URL"), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source); transactions = new TransactionTemplate(manager);
        var configuration = new Configuration();
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(IdentityRepository.class); configuration.addMapper(ModelConnectionRepository.class); configuration.addMapper(ModelConfigurationRepository.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void seed() {
        owner = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES (?::uuid,?,'fixture','USER',false)", owner, "u_" + owner.substring(0, 20));
        var identity = mock(IdentityTransactionService.class);
        var locks = transactional(new IdentityTransactionService(sql.getMapper(IdentityRepository.class), clock, mock(IdentityRevocationNotifier.class),
            mock(RevocationIntentRepository.class), mock(IdentityRevocationBarrier.class)), IdentityTransactionService.class);
        var grant = new SessionAuthorizationDTO(owner, "USER", false, 1, NOW.plusSeconds(3600));
        when(identity.authorizeMutation("session")).thenAnswer(call -> { locks.lockResourceOwner(owner); return grant; });
        when(identity.authorizePrivateDelivery("session")).thenAnswer(call -> { locks.lockResourceOwner(owner); return grant; });
        var secrets = mock(IdentitySecrets.class);
        when(secrets.fingerprint(anyString())).thenAnswer(call -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(call.<String>getArgument(0).getBytes(StandardCharsets.UTF_8))));
        var cipher = new ModelCredentialCipher(new ModelSecurityProperties("fixture", "fixture:" + Base64.getEncoder().encodeToString(new byte[32])));
        var repository = sql.getMapper(ModelConnectionRepository.class);
        var receipts = new ModelCommandReceipts(repository, secrets, new ObjectMapper(), clock);
        service = transactional(new ModelConnectionService(repository, identity, cipher, new ModelConnectionValidator(), receipts, clock), ModelConnectionService.class);
        models = transactional(new ModelConfigurationService(sql.getMapper(ModelConfigurationRepository.class), repository, identity,
            new ModelConnectionValidator(), receipts, clock), ModelConfigurationService.class);
    }

    @Test
    void ciphertextAndFullReceiptPersistWithoutPlaintextAndReplayIsStable() throws Exception {
        var created = service.create("session", "key", input);
        assertThat(service.create("session", "key", input)).isEqualTo(created);
        assertThat(service.connection("session", created.id())).isEqualTo(created);
        assertThat(new ObjectMapper().writeValueAsString(created)).doesNotContain(input.apiKey());
        assertThat(jdbc.queryForObject("SELECT credential_ciphertext FROM personal_model_connection WHERE id=?::uuid", String.class, created.id())).doesNotContain(input.apiKey());
        assertThat(jdbc.queryForObject("SELECT response::text FROM personal_model_command WHERE user_id=?::uuid", String.class, owner)).doesNotContain(input.apiKey());
        assertThat(service.connections("session", null).connections()).containsExactly(created);
    }

    @Test
    void competingCreateKeysForTheSameNameSerializeAtTheActualOwnerLock() throws Exception {
        var outcomes = race(() -> service.create("session", "one", input), () -> service.create("session", "two", input));
        assertThat(outcomes.stream().filter(ModelConnectionResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes).contains("MODEL_NAME_CONFLICT");
    }

    @Test
    void sameCreationKeyConvergesUnderActualConcurrentTransactions() throws Exception {
        var outcomes = race(() -> service.create("session", "same", input), () -> service.create("session", "same", input));
        assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_connection WHERE user_id=?::uuid", Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void rollbackCannotLeaveAConnectionOrAnAcceptedCommandBehind() {
        assertThatThrownBy(() -> transactions.execute(status -> {
            service.create("session", "rollback", input); throw new IllegalStateException("injected rollback");
        })).hasMessage("injected rollback");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_connection WHERE user_id=?::uuid", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_command WHERE user_id=?::uuid", Integer.class, owner)).isZero();
    }

    @Test
    void updatingThenDeletingLeavesNoCredentialAndLateWritesCannotRestoreIt() {
        var created = service.create("session", "create", input);
        var updated = service.update("session", created.id(), "update", new ModelConnectionRequest("renamed", input.baseUrl(), input.protocol(), input.preset(), "unverified-replacement", "1"));
        assertThat(updated.configurationVersion()).isEqualTo("2");
        assertThat(service.delete("session", created.id(), "delete", "2").deleted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT credential_ciphertext FROM personal_model_connection WHERE id=?::uuid", String.class, created.id())).isNull();
        assertThatThrownBy(() -> service.update("session", created.id(), "late", input)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_CONNECTION_NOT_FOUND"));
    }

    @Test
    void receiptBodyExpiryRetainsTheCreateIdentityInsteadOfReexecutingIt() {
        service.create("session", "expired", input);
        jdbc.update("UPDATE personal_model_command SET completed_at=? WHERE user_id=?::uuid", Timestamp.from(NOW.minusSeconds(8 * 86400)), owner);
        service.expireReceipts();
        assertThatThrownBy(() -> service.create("session", "expired", input)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_RECEIPT_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_connection WHERE user_id=?::uuid", Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void sharedCredentialModelsAndBothDeletionScopesPersistWithoutPhysicalCascades() {
        var connection = service.create("session", "connection", input);
        String encrypted = sql.getMapper(ModelConnectionRepository.class).find(connection.id()).credentialCiphertext();
        var first = models.create("session", connection.id(), "flash", model("Flash", "deepseek-flash", null));
        var second = models.create("session", connection.id(), "pro", model("Pro", "deepseek-v4-pro", null));
        assertThat(models.configuration("session", first.id())).isEqualTo(first);
        assertThat(sql.getMapper(ModelConnectionRepository.class).find(connection.id()).credentialCiphertext()).isEqualTo(encrypted);
        models.delete("session", first.id(), "delete-model", "1");
        assertThat(models.configurations("session", connection.id(), null).models()).containsExactly(second);
        assertThat(service.connection("session", connection.id()).keyConfigured()).isTrue();
        service.delete("session", connection.id(), "delete-connection", "1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_configuration WHERE connection_id=?::uuid AND deleted_at IS NULL", Integer.class, connection.id())).isZero();
        assertThatThrownBy(() -> models.update("session", second.id(), "late-update", model("Pro", second.modelId(), "1")))
            .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo("MODEL_CONFIGURATION_NOT_FOUND"));
    }

    @Test
    void modelDisplayAndCallConfigurationVersionsHaveDifferentPersistenceSemantics() {
        var connection = service.create("session", "connection", input);
        var first = models.create("session", connection.id(), "create", model("Flash", "deepseek-flash", null));
        var renamed = models.update("session", first.id(), "rename", model("New name", first.modelId(), "1"));
        assertThat(renamed.configurationVersion()).isEqualTo("1");
        var changed = models.update("session", first.id(), "change", model("New name", "deepseek-v4-pro", "2"));
        assertThat(changed.configurationVersion()).isEqualTo("2");
        assertThat(models.update("session", first.id(), "change", model("New name", "deepseek-v4-pro", "2"))).isEqualTo(changed);
    }

    @Test
    void successfulModelCommandsReplayAfterParentProtocolChangesAndDeletionWithoutRevival() {
        var connection = service.create("session", "connection", new ModelConnectionRequest("Anthropic", "https://example.test", ModelProtocol.ANTHROPIC, ModelProviderPreset.CUSTOM, "fixture-provider-key", null));
        var request = new ModelConfigurationRequest("Thinking", "fixture", 100000, 4096, ModelThinkingMode.ON, null, 2048, null, null);
        var created = models.create("session", connection.id(), "create", request);
        var edit = new ModelConfigurationRequest("Renamed", "fixture", 100000, 4096, ModelThinkingMode.ON, null, 2048, null, "1");
        var updated = models.update("session", created.id(), "update", edit);
        service.update("session", connection.id(), "change-protocol", new ModelConnectionRequest("Chat", "https://example.test", ModelProtocol.OPENAI_CHAT, ModelProviderPreset.CUSTOM, null, "1"));
        assertThat(models.create("session", connection.id(), "create", request)).isEqualTo(created);
        assertThat(models.update("session", created.id(), "update", edit)).isEqualTo(updated);
        service.delete("session", connection.id(), "delete", "2");
        assertThat(models.create("session", connection.id(), "create", request)).isEqualTo(created);
        assertThat(models.update("session", created.id(), "update", edit)).isEqualTo(updated);
        assertThatThrownBy(() -> models.create("session", connection.id(), "fresh", request)).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("MODEL_CONNECTION_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_model_configuration WHERE connection_id=?::uuid AND deleted_at IS NULL", Integer.class, connection.id())).isZero();
    }

    private ModelConfigurationRequest model(String name, String id, String version) {
        return new ModelConfigurationRequest(name, id, 1000000, 4096, ModelThinkingMode.OFF, null, null, null, version);
    }

    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> invoke(barrier, first)); var b = executor.submit(() -> invoke(barrier, second));
            return List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        }
    }
    private Object invoke(CyclicBarrier barrier, Callable<Object> operation) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try { return operation.call(); } catch (BusinessException rejected) { return rejected.code(); }
    }
    private <T> T transactional(T target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
}
