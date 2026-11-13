package ai.codecraft.modules.modelaccess.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.modelaccess.model.entity.ModelConfigurationEntity;
import ai.codecraft.modules.modelaccess.model.entity.ModelConnectionEntity;
import ai.codecraft.modules.modelaccess.repository.ModelConfigurationRepository;
import ai.codecraft.modules.modelaccess.repository.ModelConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelConfigurationServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ModelConfigurationRepository models = mock(ModelConfigurationRepository.class);
    private final ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final List<ModelConfigurationEntity> stored = new ArrayList<>();
    private ModelConfigurationService service;

    @BeforeEach
    void setup() {
        var owner = new SessionAuthorizationDTO("owner", "USER", false, 1, NOW.plusSeconds(60));
        when(identity.authorizeMutation("session")).thenReturn(owner);
        when(identity.authorizePrivateDelivery("session")).thenReturn(owner);
        when(identity.authorizePrivateDelivery("other")).thenReturn(new SessionAuthorizationDTO("other", "USER", false, 1, NOW.plusSeconds(60)));
        when(connections.find("connection")).thenReturn(new ModelConnectionEntity("connection", "owner", "DeepSeek", "https://api.deepseek.com", "OPENAI_CHAT", "DEEPSEEK",
            "fixture", "nonce", "single-encrypted-key", 1, 1, NOW, NOW, null));
        doAnswer(call -> { stored.add(call.getArgument(0)); return null; }).when(models).insert(any());
        when(models.find(anyString())).thenAnswer(call -> stored.stream().filter(value -> value.id().equals(call.getArgument(0))).findFirst().orElse(null));
        when(models.update(any(), anyLong())).thenAnswer(call -> {
            ModelConfigurationEntity incoming = call.getArgument(0);
            int index = -1;
            for (int i = 0; i < stored.size(); i++) if (stored.get(i).id().equals(incoming.id())) index = i;
            if (index < 0 || stored.get(index).version() != call.<Long>getArgument(1)) return 0;
            stored.set(index, incoming); return 1;
        });
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ModelConfigurationService(models, connections, identity, new ModelConnectionValidator(),
            new ModelCommandReceipts(connections, mock(IdentitySecrets.class), new ObjectMapper(), clock), clock);
    }

    @Test
    void oneEncryptedConnectionSupportsSeparateModelsWithoutCopyingOrReplacingItsCredential() {
        var flash = service.create("session", "connection", "flash", input("Flash", "deepseek-flash"));
        var pro = service.create("session", "connection", "pro", input("Pro", "deepseek-v4-pro"));
        assertEquals("connection", flash.connectionId()); assertEquals("connection", pro.connectionId());
        assertEquals(2, stored.size());
        assertEquals(flash, service.configuration("session", flash.id()));
        assertEquals("MODEL_CONFIGURATION_NOT_FOUND", assertThrows(BusinessException.class, () -> service.configuration("other", flash.id())).code());
        verify(connections, never()).insert(any()); verify(connections, never()).update(any(), anyLong());
    }

    @Test
    void invalidWindowOrOutputParametersCannotCreateAnApparentlyUsableModel() {
        var invalid = new ModelConfigurationRequest("small", "custom", 100, 200, ModelThinkingMode.OFF, null, null, null, null);
        assertEquals("MODEL_INVALID_REQUEST", assertThrows(BusinessException.class, () -> service.create("session", "connection", "bad", invalid)).code());
        verify(models, never()).insert(any());
    }

    @Test
    void displayRenamePreservesChecksWhileModelChangesInvalidateTheirConfigurationIdentity() {
        var created = service.create("session", "connection", "create", input("Flash", "deepseek-flash"));
        var renamed = service.update("session", created.id(), "rename", new ModelConfigurationRequest("other name", created.modelId(), 1000000, 4096, ModelThinkingMode.OFF, null, null, null, "1"));
        assertEquals("1", renamed.configurationVersion()); assertEquals("2", renamed.version());
        var updated = service.update("session", created.id(), "update", new ModelConfigurationRequest("other name", "deepseek-v4-pro", 1000000, 4096, ModelThinkingMode.ON, null, null, null, "2"));
        assertEquals("2", updated.configurationVersion());
        verify(connections, never()).update(any(), anyLong());
    }

    @Test
    void deletingOneConfigurationDoesNotRemoveItsConnectionOrOtherModels() {
        var first = service.create("session", "connection", "one", input("Flash", "deepseek-flash"));
        var second = service.create("session", "connection", "two", input("Pro", "deepseek-v4-pro"));
        assertEquals(true, service.delete("session", first.id(), "delete", "1").deleted());
        assertEquals(true, service.delete("session", first.id(), "another-delete", "1").deleted());
        assertEquals(second, service.configuration("session", second.id()));
        assertEquals("MODEL_CONFIGURATION_NOT_FOUND", assertThrows(BusinessException.class, () -> service.configuration("session", first.id())).code());
        verify(models, times(1)).update(any(), anyLong());
        verify(connections, never()).update(any(), anyLong());
    }

    private ModelConfigurationRequest input(String name, String id) {
        return new ModelConfigurationRequest(name, id, 1000000, 4096, ModelThinkingMode.OFF, null, null, null, null);
    }
}
