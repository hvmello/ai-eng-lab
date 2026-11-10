package io.github.hvmello.aienglab.agentguardrails;

/**
 * Renders validated, typed arguments for a human, written by the application. It never receives raw model text.
 *
 * @param <A> the typed arguments of the tool
 */
@FunctionalInterface
public interface ApprovalSummary<A> {

    String summarize(A arguments);

    /** The full payload of the action, shown to the approver on its own. Empty when the summary says it all. */
    default String detail(A arguments) {
        return "";
    }
}
