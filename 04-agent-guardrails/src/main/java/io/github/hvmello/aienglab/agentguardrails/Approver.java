package io.github.hvmello.aienglab.agentguardrails;

/** Decides whether a write may run. The default is {@link #denyAll()}. */
public interface Approver {

    enum Decision { APPROVED, DENIED }

    Decision decide(ApprovalRequest request);

    /** Refuses every write. This is what an agent gets unless it is given something else. */
    static Approver denyAll() {
        return request -> Decision.DENIED;
    }
}
