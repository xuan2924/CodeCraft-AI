package ai.codecraft.modules.workspace.model.dto;

import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;

/** Explicit selection; never contains credentials or implies a successful paid check. */
public record ExerciseModelResponse(ModelInvocationReferenceDTO selected, String availability) {}
