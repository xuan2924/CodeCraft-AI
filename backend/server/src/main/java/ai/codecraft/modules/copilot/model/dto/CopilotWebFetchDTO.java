package ai.codecraft.modules.copilot.model.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CopilotWebFetchDTO(String code, JsonNode body) {}
