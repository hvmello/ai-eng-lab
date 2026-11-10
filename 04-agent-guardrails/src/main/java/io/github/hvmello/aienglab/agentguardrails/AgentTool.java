package io.github.hvmello.aienglab.agentguardrails;

import io.github.hvmello.aienglab.toolcalling.Tool;
import java.util.Objects;

/**
 * A {@link Tool} plus the permission it needs and, for a write, how to show it to the approver.
 *
 * @param <A> the typed arguments of the tool
 */
public record AgentTool<A>(Tool<A> tool, Permission permission, ApprovalSummary<A> summary) {

    public AgentTool {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(permission, "permission: every tool must declare one");
        if (permission == Permission.WRITE) {
            Objects.requireNonNull(summary, "a write tool needs an approval summary");
        }
    }

    public static <A> AgentTool<A> read(Tool<A> tool) {
        return new AgentTool<>(tool, Permission.READ, null);
    }

    public static <A> AgentTool<A> write(Tool<A> tool, ApprovalSummary<A> summary) {
        return new AgentTool<>(tool, Permission.WRITE, summary);
    }

    public String name() {
        return tool.spec().name();
    }
}
