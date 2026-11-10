package io.github.hvmello.aienglab.agentguardrails;

import java.util.ArrayList;
import java.util.List;

/** Records every request and answers with a fixed decision. */
final class StubApprover implements Approver {

    final List<ApprovalRequest> requests = new ArrayList<>();
    private final Decision decision;

    private StubApprover(Decision decision) {
        this.decision = decision;
    }

    static StubApprover approving() {
        return new StubApprover(Decision.APPROVED);
    }

    static StubApprover denying() {
        return new StubApprover(Decision.DENIED);
    }

    @Override
    public Decision decide(ApprovalRequest request) {
        requests.add(request);
        return decision;
    }
}
