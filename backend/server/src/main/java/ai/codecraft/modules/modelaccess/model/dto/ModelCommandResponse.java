package ai.codecraft.modules.modelaccess.model.dto;

/** Only non-secret configuration results may be persisted as command receipts. */
public sealed interface ModelCommandResponse permits ModelConnectionResponse, ModelConfigurationResponse, ModelCheckResponse {}
