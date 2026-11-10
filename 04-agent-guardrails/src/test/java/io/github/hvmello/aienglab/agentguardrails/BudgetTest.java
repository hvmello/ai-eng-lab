package io.github.hvmello.aienglab.agentguardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hvmello.aienglab.agentguardrails.AgentOutcome.BudgetExhausted.Reason;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.TokenUsage;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BudgetTest {

    private static ChatResult usage(int in, int out) {
        return new ChatResult("", FinishReason.STOP, new TokenUsage(in, out, in + out));
    }

    @Test
    void defaultsAreValid() {
        var budget = Budget.defaults();

        assertThat(budget.maxSteps()).isEqualTo(6);
        assertThat(budget.costModel()).isNull();
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> Budget.defaults().withMaxSteps(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.defaults().withMaxToolCallsTotal(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.defaults().withMaxTotalTokens(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.defaults().withMaxApprovalRequests(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.defaults().withToolTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void costModelAndCostLimitComeTogether() {
        var model = new CostModel(BigDecimal.ONE, BigDecimal.ONE);

        assertThatThrownBy(() -> Budget.defaults().withCost(model, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.defaults().withCost(null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.defaults().withCost(model, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void costIsExact() {
        var model = new CostModel(new BigDecimal("3"), new BigDecimal("15"));

        assertThat(model.cost(1_000_000, 1_000_000)).isEqualByComparingTo("18");
        assertThat(model.cost(1, 1)).isEqualByComparingTo("0.000018");
        assertThat(model.cost(0, 0)).isEqualByComparingTo("0");
    }

    @Test
    void negativePricesAreRejected() {
        assertThatThrownBy(() -> new CostModel(new BigDecimal("-1"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMeterAddsUsageAndReportsTheLimit() {
        var meter = new BudgetMeter(Budget.defaults().withMaxTotalTokens(100));

        assertThat(meter.record(usage(30, 10))).isEmpty();
        assertThat(meter.beforeCall()).isEmpty();
        assertThat(meter.record(usage(50, 10))).contains(Reason.TOKENS);
        assertThat(meter.beforeCall()).contains(Reason.TOKENS);
        assertThat(meter.spend().totalTokens()).isEqualTo(100);
        assertThat(meter.spend().steps()).isEqualTo(2);
    }

    @Test
    void theMeterNeverAsksForMoreOutputThanIsLeft() {
        var meter = new BudgetMeter(Budget.defaults().withMaxTotalTokens(100).withMaxOutputTokens(80));

        assertThat(meter.parameters().maxOutputTokens()).isEqualTo(80);
        meter.record(usage(30, 10));
        assertThat(meter.parameters().maxOutputTokens()).isEqualTo(60);
    }

    @Test
    void theMeterReportsMissingUsage() {
        var meter = new BudgetMeter(Budget.defaults());

        assertThat(meter.record(new ChatResult("", FinishReason.STOP, null))).contains(Reason.USAGE_UNREPORTED);
    }

    @Test
    void spendPrintsOnOneLine() {
        var spend = new Spend(2, 3, 100, 20, new BigDecimal("0.0120"));

        assertThat(spend.toString()).isEqualTo("steps=2 toolCalls=3 tokens=120 costUsd=0.012");
    }
}
