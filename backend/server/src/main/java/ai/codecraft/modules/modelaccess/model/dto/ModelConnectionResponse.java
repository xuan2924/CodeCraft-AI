package ai.codecraft.modules.modelaccess.model.dto;

import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;

public record ModelConnectionResponse(String id, String name, String baseUrl, ModelProtocol protocol, ModelProviderPreset preset,
    String version, String configurationVersion, boolean keyConfigured, boolean deleted) implements ModelCommandResponse {}
