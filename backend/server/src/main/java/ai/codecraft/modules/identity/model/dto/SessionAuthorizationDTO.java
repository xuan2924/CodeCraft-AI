package ai.codecraft.modules.identity.model.dto;

import java.time.Instant;

/** Minimal authorization snapshot; callers must recheck before sending private data. */
public record SessionAuthorizationDTO(String userId, String role, boolean passwordChangeRequired,
                                      long authVersion, Instant expiresAt) { }
