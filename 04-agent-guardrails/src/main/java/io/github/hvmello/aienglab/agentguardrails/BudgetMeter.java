package io.github.hvmello.aienglab.agentguardrails;

import io.github.hvmello.aienglab.agentguardrails.AgentOutcome.BudgetExhausted.Reason;
import io.github.hvmello.aienglab.shared.ChatParameters;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.TokenUsage;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Adds up what the provider reports after each model call. Metering is after the fact: one call can overshoot,
 * and {@link #parameters()} only bounds the output side of the next one. A provider that reports no usage makes the
 * run fail closed, because a budget that cannot be measured is not a budget.
 */
final class BudgetMeter {

    private final Budget budget;
    private int steps;
    private int toolCalls;
    private long inputTokens;
    private long outputTokens;
    private BigDecimal cost = BigDecimal.ZERO;

    BudgetMeter(Budget budget) {
        this.budget = budget;
    }

    /** Why the next model call must not happen, if a limit is already spent. */
    Optional<Reason> beforeCall() {
        return limitReached();
    }

    /** Output cap for the next call: the configured ceiling, or what is left of the token budget. */
    ChatParameters parameters() {
        long remaining = budget.maxTotalTokens() - inputTokens - outputTokens;
        return new ChatParameters(null, null, (int) Math.min(budget.maxOutputTokens(), Math.max(1, remaining)));
    }

    /** Counts one model call. Returns the reason to stop, if the usage is missing or a limit is now spent. */
    Optional<Reason> record(ChatResult result) {
        steps++;
        TokenUsage usage = result.usage();
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) {
            return Optional.of(Reason.USAGE_UNREPORTED);
        }
        inputTokens += usage.inputTokens();
        outputTokens += usage.outputTokens();
        if (budget.costModel() != null) {
            cost = budget.costModel().cost(inputTokens, outputTokens);
        }
        return limitReached();
    }

    void countToolCall() {
        toolCalls++;
    }

    boolean toolCallsExhausted() {
        return toolCalls >= budget.maxToolCallsTotal();
    }

    Spend spend() {
        return new Spend(steps, toolCalls, inputTokens, outputTokens, cost);
    }

    private Optional<Reason> limitReached() {
        if (inputTokens + outputTokens >= budget.maxTotalTokens()) {
            return Optional.of(Reason.TOKENS);
        }
        if (budget.maxCostUsd() != null && cost.compareTo(budget.maxCostUsd()) >= 0) {
            return Optional.of(Reason.COST);
        }
        return Optional.empty();
    }
}
