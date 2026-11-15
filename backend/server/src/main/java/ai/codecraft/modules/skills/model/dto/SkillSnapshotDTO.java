package ai.codecraft.modules.skills.model.dto;

public record SkillSnapshotDTO(String skillId, String versionId, String name, String description,
    boolean explicitlySelected, boolean explicitLoaded) {}
