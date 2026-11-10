package io.github.hvmello.aienglab.agentguardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hvmello.aienglab.shared.ToolSpec;
import io.github.hvmello.aienglab.toolcalling.FieldProblem;
import io.github.hvmello.aienglab.toolcalling.InvalidArgumentsException;
import io.github.hvmello.aienglab.toolcalling.Tool;
import io.github.hvmello.aienglab.toolcalling.ToolFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** READ: the names of all notes. Takes no arguments. */
public final class ListNotesTool implements Tool<ListNotesTool.Args> {

    /** No fields. */
    public record Args() {}

    private static final ToolSpec SPEC = new ToolSpec(
            "list_notes",
            "Lists the names of all notes.",
            "{\"type\":\"object\",\"properties\":{}}");

    private final NoteStore store;

    public ListNotesTool(NoteStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public AgentTool<Args> asAgentTool() {
        return AgentTool.read(this);
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Args parse(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new InvalidArgumentsException("", "arguments must be a JSON object");
        }
        List<FieldProblem> problems = new ArrayList<>();
        arguments.fieldNames().forEachRemaining(
                name -> problems.add(new FieldProblem(name, "is not a known field")));
        if (!problems.isEmpty()) {
            throw new InvalidArgumentsException(problems);
        }
        return new Args();
    }

    @Override
    public JsonNode execute(Args arguments) throws ToolFailure {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        var names = data.putArray("notes");
        store.names().forEach(names::add);
        return data;
    }
}
