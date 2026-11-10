package io.github.hvmello.aienglab.agentguardrails;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Prices in USD per one million tokens. The arithmetic is exact ({@link BigDecimal}), never floating point.
 * On Ollama there is no price, so a run has no cost model and the token cap does the work.
 */
public record CostModel(BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {

    public CostModel {
        Objects.requireNonNull(inputUsdPerMillion, "inputUsdPerMillion");
        Objects.requireNonNull(outputUsdPerMillion, "outputUsdPerMillion");
        if (inputUsdPerMillion.signum() < 0 || outputUsdPerMillion.signum() < 0) {
            throw new IllegalArgumentException("prices must not be negative");
        }
    }

    public BigDecimal cost(long inputTokens, long outputTokens) {
        return inputUsdPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputUsdPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                .movePointLeft(6);
    }
}
