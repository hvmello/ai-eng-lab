package io.github.hvmello.aienglab.agentguardrails;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * Every bound of one agent run.
 *
 * @param maxSteps model calls allowed in one run
 * @param maxToolCallsPerStep tool calls executed from one model answer; the excess is refused, retryable
 * @param maxToolCallsTotal tool calls in the whole run; the call that would exceed it ends the run
 * @param maxFailuresPerTool failures of one tool in one run before it is cut off
 * @param toolTimeout how long one tool execution may take
 * @param maxTotalTokens input plus output tokens, added up after each model call
 * @param maxOutputTokens ceiling for one model answer; the real cap is the smaller of this and the tokens left
 * @param costModel prices, or null when the run has no cost (Ollama)
 * @param maxCostUsd the spend that ends the run, or null; set together with {@code costModel}
 * @param maxApprovalRequests how many times the approver is asked in one run; past it, writes are denied unasked
 */
public record Budget(
        int maxSteps,
        int maxToolCallsPerStep,
        int maxToolCallsTotal,
        int maxFailuresPerTool,
        Duration toolTimeout,
        long maxTotalTokens,
        int maxOutputTokens,
        CostModel costModel,
        BigDecimal maxCostUsd,
        int maxApprovalRequests) {

    public Budget {
        if (maxSteps < 1 || maxToolCallsPerStep < 1 || maxToolCallsTotal < 1 || maxFailuresPerTool < 1
                || maxOutputTokens < 1) {
            throw new IllegalArgumentException("limits must be at least 1");
        }
        if (maxTotalTokens < 1) {
            throw new IllegalArgumentException("maxTotalTokens must be at least 1");
        }
        if (maxApprovalRequests < 0) {
            throw new IllegalArgumentException("maxApprovalRequests must not be negative");
        }
        Objects.requireNonNull(toolTimeout, "toolTimeout");
        if (toolTimeout.isNegative() || toolTimeout.isZero()) {
            throw new IllegalArgumentException("toolTimeout must be positive");
        }
        if ((costModel == null) != (maxCostUsd == null)) {
            throw new IllegalArgumentException("costModel and maxCostUsd are set together or not at all");
        }
        if (maxCostUsd != null && maxCostUsd.signum() <= 0) {
            throw new IllegalArgumentException("maxCostUsd must be positive");
        }
    }

    /** 6 steps, 4 calls per step, 12 calls, 2 failures per tool, 10 s per tool, 20k tokens, 1k per answer, 3 approvals. */
    public static Budget defaults() {
        return new Budget(6, 4, 12, 2, Duration.ofSeconds(10), 20_000, 1_024, null, null, 3);
    }

    public Budget withMaxSteps(int value) {
        return new Budget(value, maxToolCallsPerStep, maxToolCallsTotal, maxFailuresPerTool, toolTimeout,
                maxTotalTokens, maxOutputTokens, costModel, maxCostUsd, maxApprovalRequests);
    }

    public Budget withMaxToolCallsPerStep(int value) {
        return new Budget(maxSteps, value, maxToolCallsTotal, maxFailuresPerTool, toolTimeout,
                maxTotalTokens, maxOutputTokens, costModel, maxCostUsd, maxApprovalRequests);
    }

    public Budget withMaxToolCallsTotal(int value) {
        return new Budget(maxSteps, maxToolCallsPerStep, value, maxFailuresPerTool, toolTimeout,
                maxTotalTokens, maxOutputTokens, costModel, maxCostUsd, maxApprovalRequests);
    }

    public Budget withMaxFailuresPerTool(int value) {
        return new Budget(maxSteps, maxToolCallsPerStep, maxToolCallsTotal, value, toolTimeout,
                maxTotalTokens, maxOutputTokens, costModel, maxCostUsd, maxApprovalRequests);
    }

    public Budget withToolTimeout(Duration value) {
        return new Budget(maxSteps, maxToolCallsPerStep, maxToolCallsTotal, maxFailuresPerTool, value,
                maxTotalTokens, maxOutputTokens, costModel, maxCostUsd, maxApprovalRequests);
    }

    public Budget withMaxTotalTokens(long value) {
        return new Budget(maxSteps, maxToolCallsPerStep, maxToolCallsTotal, maxFailuresPerTool, toolTimeout,
                value, maxOutputTokens, costModel, maxCostUsd, maxApprovalRequests);
    }

    public Budget withMaxOutputTokens(int value) {
        return new Budget(maxSteps, maxToolCallsPerStep, maxToolCallsTotal, maxFailuresPerTool, toolTimeout,
                maxTotalTokens, value, costModel, maxCostUsd, maxApprovalRequests);
    }

    public Budget withCost(CostModel model, BigDecimal maxCost) {
        return new Budget(maxSteps, maxToolCallsPerStep, maxToolCallsTotal, maxFailuresPerTool, toolTimeout,
                maxTotalTokens, maxOutputTokens, model, maxCost, maxApprovalRequests);
    }

    public Budget withMaxApprovalRequests(int value) {
        return new Budget(maxSteps, maxToolCallsPerStep, maxToolCallsTotal, maxFailuresPerTool, toolTimeout,
                maxTotalTokens, maxOutputTokens, costModel, maxCostUsd, value);
    }
}
