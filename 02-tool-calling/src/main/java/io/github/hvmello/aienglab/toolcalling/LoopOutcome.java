package io.github.hvmello.aienglab.toolcalling;

import io.github.hvmello.aienglab.shared.Message;
import java.util.List;

/** How a loop run ended. Only {@link Answered} carries an answer. */
public sealed interface LoopOutcome {

    /** Every message of the run, in order, tool results included. */
    List<Message> transcript();

    /** The model answered in text. */
    record Answered(String text, List<Message> transcript) implements LoopOutcome {
        public Answered {
            transcript = List.copyOf(transcript);
        }
    }

    /** The model was still asking for tools when the step limit was reached. There is no answer. */
    record StepLimitReached(List<Message> transcript) implements LoopOutcome {
        public StepLimitReached {
            transcript = List.copyOf(transcript);
        }
    }

    /** The model output was cut off (finish reason LENGTH), so nothing it said can be trusted as complete. */
    record Truncated(List<Message> transcript) implements LoopOutcome {
        public Truncated {
            transcript = List.copyOf(transcript);
        }
    }
}
