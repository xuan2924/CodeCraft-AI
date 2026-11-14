package ai.codecraft.modules.modelaccess.model.dto;

public record ModelCheckStatusResponse(ModelCheckResponse check, String reason, boolean currentConfiguration,
    ModelCheckItemResponse basic, ModelCheckItemResponse tool) { }
