package ai.codecraft.modules.modelaccess.model.dto;

/** Caller-owned policy caps; a probe may lower the output and thinking budgets. */
public record ModelInvocationLimitsDTO(int outputTokens, Integer thinkingBudgetTokens) {}
