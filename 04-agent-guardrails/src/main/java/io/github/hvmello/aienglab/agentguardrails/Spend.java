package io.github.hvmello.aienglab.agentguardrails;

import java.math.BigDecimal;

/** What a run used, as reported by the provider and counted by the agent. Cost is zero without a cost model. */
public record Spend(int steps, int toolCalls, long inputTokens, long outputTokens, BigDecimal costUsd) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    @Override
    public String toString() {
        return "steps=" + steps + " toolCalls=" + toolCalls + " tokens=" + totalTokens()
                + " costUsd=" + costUsd.stripTrailingZeros().toPlainString();
    }
}
