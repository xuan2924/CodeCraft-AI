package ai.codecraft.modules.modelaccess.model.dto;

import ai.codecraft.modules.modelaccess.model.ModelProtocol;
import ai.codecraft.modules.modelaccess.model.ModelProviderPreset;

public record ModelConnectionRequest(String name, String baseUrl, ModelProtocol protocol, ModelProviderPreset preset,
    String apiKey, String expectedVersion) {
    @Override public String toString() { return "ModelConnectionRequest[REDACTED]"; }
}
