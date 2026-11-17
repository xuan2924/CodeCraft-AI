package ai.codecraft.infrastructure.tasks;

/** A disposable scheduling hint, never an instruction to bypass a durable claim. */
public record TaskWakeup(Lane lane) {
    public enum Lane { COPILOT, INTERVIEWER, EVALUATION }
}
