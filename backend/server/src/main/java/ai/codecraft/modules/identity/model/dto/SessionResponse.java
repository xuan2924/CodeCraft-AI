package ai.codecraft.modules.identity.model.dto;

import java.time.Instant;

public record SessionResponse(String id, String username, String role, boolean passwordChangeRequired,
                              Instant expiresAt) { }
