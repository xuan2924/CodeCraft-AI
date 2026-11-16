package ai.codecraft.modules.identity.model.dto;

import java.time.Instant;

public record CaptchaResponse(String id, String image, Instant expiresAt) { }
