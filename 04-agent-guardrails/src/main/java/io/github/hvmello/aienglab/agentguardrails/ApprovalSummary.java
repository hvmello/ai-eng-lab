package io.github.hvmello.aienglab.agentguardrails;

/**
 * Renders validated, typed arguments for a human, written by the application. It never receives raw model text.
 *
 * @param <A> the typed arguments of the tool
 */
@FunctionalInterface
public interface ApprovalSummary<A> {

    String summarize(A arguments);
}
