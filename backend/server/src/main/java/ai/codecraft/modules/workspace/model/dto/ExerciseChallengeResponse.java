package ai.codecraft.modules.workspace.model.dto;

public record ExerciseChallengeResponse(boolean available, boolean entered, boolean eligible, String reason,
    String enteredRevision, String baseTestId, ProblemResponse publishedChallenge) {}
