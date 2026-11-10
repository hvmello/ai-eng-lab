package io.github.hvmello.aienglab.agentguardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hvmello.aienglab.shared.ToolSpec;
import io.github.hvmello.aienglab.toolcalling.Arguments;
import io.github.hvmello.aienglab.toolcalling.Tool;
import io.github.hvmello.aienglab.toolcalling.ToolFailure;
import java.util.concurrent.atomic.AtomicInteger;

/** A fake tool that counts how often it really ran. */
final class CountingTool implements Tool<CountingTool.Args> {

    record Args(String text) {
        Args {
            new Arguments.Checks().require(text != null, "text", "is required").verify();
        }
    }

    interface Behaviour {
        JsonNode run(Args args) throws Exception;
    }

    final String name;
    final AtomicInteger executions = new AtomicInteger();
    Behaviour behaviour = args -> JsonNodeFactory.instance.objectNode().put("echo", args.text());

    CountingTool(String name) {
        this.name = name;
    }

    AgentTool<Args> asRead() {
        return AgentTool.read(this);
    }

    AgentTool<Args> asWrite() {
        return AgentTool.write(this, args -> "run " + name + " with " + args.text());
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(name, "test tool", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
    }

    @Override
    public Args parse(JsonNode arguments) {
        return Arguments.parse(arguments, Args.class);
    }

    @Override
    public JsonNode execute(Args arguments) throws ToolFailure {
        executions.incrementAndGet();
        try {
            return behaviour.run(arguments);
        } catch (ToolFailure e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
