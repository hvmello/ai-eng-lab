package io.github.hvmello.aienglab.agentguardrails;

import io.github.hvmello.aienglab.shared.Message;
import java.util.List;

/** How an agent run ended. Only {@link Answered} carries an answer. Each outcome carries what the run spent. */
public sealed interface AgentOutcome {

    /** Every message of the run, in order, tool results included. */
    List<Message> transcript();

    Spend spend();

    /** The model answered in text. */
    record Answered(String text, List<Message> transcript, Spend spend) implements AgentOutcome {
        public Answered {
            transcript = List.copyOf(transcript);
        }
    }

    /** The model was still asking for tools when the step limit was reached. */
    record StepLimitReached(List<Message> transcript, Spend spend) implements AgentOutcome {
        public StepLimitReached {
            transcript = List.copyOf(transcript);
        }
    }

    /** A budget limit stopped the run before the tool calls it was about to run. */
    record BudgetExhausted(Reason reason, List<Message> transcript, Spend spend) implements AgentOutcome {

        public enum Reason {
            TOKENS,
            COST,
            TOOL_CALLS,
            /** The provider reported no token counts, so the budget cannot be enforced. The run stops, never guesses. */
            USAGE_UNREPORTED
        }

        public BudgetExhausted {
            transcript = List.copyOf(transcript);
        }
    }

    /** The model output was cut off (finish reason LENGTH), so nothing it said can be trusted as complete. */
    record Truncated(List<Message> transcript, Spend spend) implements AgentOutcome {
        public Truncated {
            transcript = List.copyOf(transcript);
        }
    }
}
