package ai.codecraft.modules.copilot.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CopilotTestInputDTO(@JsonProperty("expected_revision") String expectedRevision, String target) {}
