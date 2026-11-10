package io.github.hvmello.aienglab.toolcalling;

import java.time.Duration;
import java.util.Objects;

/**
 * Every bound of one loop run.
 *
 * @param maxSteps model calls allowed in one run
 * @param maxToolCallsPerStep tool calls executed from one model answer; the excess is refused
 * @param maxFailuresPerTool failures of one tool in one run before it is cut off (the circuit breaker)
 * @param toolTimeout how long one tool execution may take
 */
public record LoopLimits(int maxSteps, int maxToolCallsPerStep, int maxFailuresPerTool, Duration toolTimeout) {

    public LoopLimits {
        if (maxSteps < 1 || maxToolCallsPerStep < 1 || maxFailuresPerTool < 1) {
            throw new IllegalArgumentException("limits must be at least 1");
        }
        Objects.requireNonNull(toolTimeout, "toolTimeout");
        if (toolTimeout.isNegative() || toolTimeout.isZero()) {
            throw new IllegalArgumentException("toolTimeout must be positive");
        }
    }

    /** Conservative defaults: 6 steps, 4 calls per step, 2 failures per tool, 10 seconds per tool. */
    public static LoopLimits defaults() {
        return new LoopLimits(6, 4, 2, Duration.ofSeconds(10));
    }
}
