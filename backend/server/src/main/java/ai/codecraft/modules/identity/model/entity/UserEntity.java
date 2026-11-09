package ai.codecraft.modules.identity.model.entity;

public record UserEntity(String id, String username, String passwordHash, String role,
                         boolean passwordChangeRequired, long authVersion) {
    @Override public String toString() { return "UserEntity[redacted]"; }
}
