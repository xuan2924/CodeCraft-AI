package ai.codecraft.modules.skills.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.model.dto.SkillFileDTO;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import ai.codecraft.modules.skills.repository.SkillRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfEnvironmentVariable(named = "SKILL_IT_JDBC_URL", matches = ".+")
class SkillPersistenceIT {
    @Test
    void atomicUpdatesKeepOldVersionsOnConflictAndIdempotentCreateDoesNotConsumeQuotaAgain() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("SKILL_IT_JDBC_URL"), "fixture", "fixture");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String owner = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity_user(id,username,password_hash,role,password_change_required) VALUES (?::uuid,?,'fixture','USER',false)", owner, "skill_" + owner.substring(0, 20));
        var config = new Configuration(); config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT); config.addMapper(SkillRepository.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(factory.getObject());
        {
            var identity = mock(IdentityTransactionService.class);
            var grant = new SessionAuthorizationDTO(owner, "USER", false, 1, Instant.now().plusSeconds(3600));
            when(identity.authorizeMutation("owner")).thenAnswer(call -> { jdbc.queryForObject("SELECT id::text FROM identity_user WHERE id=?::uuid FOR UPDATE", String.class, owner); return grant; });
            when(identity.authorizePrivateDelivery("owner")).thenAnswer(call -> { jdbc.queryForObject("SELECT id::text FROM identity_user WHERE id=?::uuid FOR UPDATE", String.class, owner); return grant; });
            var limits = new SkillProperties(1, 262144, 65536, 5242880, 32, 256, 4);
            var mapper = JsonMapper.builder().build();
            var store = new SkillStore(sql.getMapper(SkillRepository.class), identity, limits, mapper, Clock.systemUTC());
            var proxy = new ProxyFactory(store); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            var service = new SkillService(new SkillPackageParser(limits), (SkillStore) proxy.getProxy(), mapper);
            var original = service.save("owner", null, "create", null, pack("first", "Original"));
            assertThat(service.save("owner", null, "create", null, pack("first", "Original"))).isEqualTo(original);
            assertThatThrownBy(() -> service.save("owner", null, "second", null, pack("second", "Other"))).hasMessageContaining("SKILL_LIMIT_EXCEEDED");
            // Arrange a retained task reference; task admission/loading is exercised separately in #18.
            String referencedTask = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO copilot_skill_snapshot(task_id,exercise_id,user_id,skill_id,version_id) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid)",
                referencedTask, UUID.randomUUID().toString(), owner, original.id(), original.contentVersion());
            var updated = service.save("owner", original.id(), "update", original.version(), pack("first", "Updated"));
            assertThat(updated.contentVersion()).isNotEqualTo(original.contentVersion());
            assertThatThrownBy(() -> service.save("owner", original.id(), "stale", original.version(), pack("first", "Stale"))).hasMessageContaining("SKILL_VERSION_CONFLICT");
            assertThat(service.contents("owner", original.id()).files().getFirst().content()).endsWith("Updated");
            assertThat(service.list("owner")).hasSize(1);
            var disabled = service.change("owner", original.id(), "disable", updated.version(), false, false);
            assertThatThrownBy(() -> service.save("owner", null, "disabled-counts", null, pack("second", "Other"))).hasMessageContaining("SKILL_LIMIT_EXCEEDED");
            service.change("owner", original.id(), "delete", disabled.version(), false, true);
            assertThat(service.list("owner")).isEmpty();
            var retained = sql.getMapper(SkillRepository.class).version(owner, original.contentVersion());
            assertThat(mapper.readValue(retained.filesPayload(), SkillPackageRequest.class).files().getFirst().content()).endsWith("Original");
            assertThat(sql.getMapper(SkillRepository.class).version(owner, updated.contentVersion())).isNull();
            assertThatThrownBy(() -> service.contents("owner", original.id())).hasMessageContaining("SKILL_NOT_FOUND");
            try (var executor = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var left = executor.submit(() -> { start.await(); return attempt(service, "left"); });
                var right = executor.submit(() -> { start.await(); return attempt(service, "right"); });
                start.countDown();
                assertThat(List.of(left.get(), right.get())).containsExactlyInAnyOrder("saved", "SKILL_LIMIT_EXCEEDED");
            }
            assertThat(service.list("owner")).hasSize(1);
            var admin = new SessionAuthorizationDTO(owner, "ADMIN", false, 1, Instant.now().plusSeconds(3600));
            when(identity.authorizePrivateDelivery("admin")).thenReturn(admin);
            when(identity.authorizeMutation("admin")).thenReturn(admin);
            assertThatThrownBy(() -> service.list("admin")).hasMessageContaining("FORBIDDEN");
            assertThatThrownBy(() -> service.save("admin", null, "admin-create", null, pack("admin", "No"))).hasMessageContaining("FORBIDDEN");
            when(identity.authorizePrivateDelivery("other")).thenReturn(new SessionAuthorizationDTO(UUID.randomUUID().toString(), "USER", false, 1, Instant.now().plusSeconds(3600)));
            assertThat(service.list("other")).isEmpty();
            assertThatThrownBy(() -> service.contents("other", service.list("owner").getFirst().id())).hasMessageContaining("SKILL_NOT_FOUND");
            var fullLimits = new SkillProperties(50, 262144, 65536, 5242880, 32, 256, 4);
            var fullStore = new SkillStore(sql.getMapper(SkillRepository.class), identity, fullLimits, mapper, Clock.systemUTC());
            var fullProxy = new ProxyFactory(fullStore); fullProxy.setProxyTargetClass(true);
            fullProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            var full = new SkillService(new SkillPackageParser(fullLimits), (SkillStore) fullProxy.getProxy(), mapper);
            for (var item : full.list("owner")) full.change("owner", item.id(), "clear-" + item.id(), item.version(), false, true);
            for (int index = 0; index < 50; index++) full.save("owner", null, "count-" + index, null, pack("item-" + index, "Small"));
            assertThat(full.list("owner")).hasSize(50);
            assertThatThrownBy(() -> full.save("owner", null, "count-overflow", null, pack("overflow", "No"))).hasMessageContaining("SKILL_LIMIT_EXCEEDED");
            for (var item : full.list("owner")) full.change("owner", item.id(), "clear-" + item.id(), item.version(), false, true);
            for (int index = 0; index < 20; index++) {
                String main = "---\nname: full-" + index + "\ndescription: Full package\n---\nBody";
                full.save("owner", null, "bytes-" + index, null, new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", main),
                    new SkillFileDTO("references/data.txt", "x".repeat(262144 - main.length())))));
            }
            assertThatThrownBy(() -> full.save("owner", null, "bytes-overflow", null, pack("overflow", "No"))).hasMessageContaining("SKILL_LIMIT_EXCEEDED");
            assertThat(full.list("owner")).hasSize(20);
            assertThat(full.list("owner").stream().mapToLong(item -> item.contentBytes()).sum()).isEqualTo(5242880);
        }
    }
    private String attempt(SkillService service, String name) {
        try { service.save("owner", null, name, null, pack(name, "Concurrent")); return "saved"; }
        catch (BusinessException failure) { return failure.code(); }
    }
    private SkillPackageRequest pack(String name, String body) {
        return new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", "---\nname: " + name + "\ndescription: Guidance\n---\n" + body)));
    }
}
