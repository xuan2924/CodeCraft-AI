package ai.codecraft.modules.identity.model.dto;

import ai.codecraft.modules.identity.model.entity.SessionEntity;
import ai.codecraft.modules.identity.model.entity.UserEntity;

/** Internal authentication snapshot; only identity() may be returned to the browser. */
public record AuthenticatedIdentityDTO(UserEntity user, SessionEntity session, SessionResponse identity) {
    @Override public String toString() { return "AuthenticatedIdentityDTO[redacted]"; }
}
