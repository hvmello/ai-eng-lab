package io.github.hvmello.aienglab.agentguardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hvmello.aienglab.shared.ToolSpec;
import io.github.hvmello.aienglab.toolcalling.Arguments;
import io.github.hvmello.aienglab.toolcalling.Tool;
import io.github.hvmello.aienglab.toolcalling.ToolFailure;
import java.util.Objects;

/** WRITE: creates or overwrites a note. The only dangerous action in this workspace. */
public final class WriteNoteTool implements Tool<WriteNoteTool.Args> {

    static final int PREVIEW_CHARS = 80;

    public record Args(String name, String content) {
        public Args {
            new Arguments.Checks()
                    .require(NoteStore.validName(name), "name", "is required and must match [a-z0-9-]{1,40}")
                    .require(content != null, "content", "is required")
                    .require(content == null || content.length() <= NoteStore.MAX_CONTENT_CHARS, "content",
                            "must be at most " + NoteStore.MAX_CONTENT_CHARS + " characters")
                    .verify();
        }
    }

    private static final ToolSpec SPEC = new ToolSpec(
            "write_note",
            "Creates a note or overwrites an existing one. A human must approve every write.",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\","
                    + "\"description\":\"Note name, lowercase letters, digits and dashes\"},"
                    + "\"content\":{\"type\":\"string\",\"description\":\"The full new content of the note\"}},"
                    + "\"required\":[\"name\",\"content\"]}");

    private final NoteStore store;

    public WriteNoteTool(NoteStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public AgentTool<Args> asAgentTool() {
        return AgentTool.write(this, this::summarize);
    }

    /** What the approver reads: the action, the target and the start of the new content. */
    String summarize(Args args) {
        String verb = store.exists(args.name()) ? "overwrite" : "create";
        String preview = args.content().length() <= PREVIEW_CHARS
                ? args.content()
                : args.content().substring(0, PREVIEW_CHARS) + "...";
        return verb + " note '" + args.name() + "' with " + args.content().length() + " characters: " + preview;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Args parse(JsonNode arguments) {
        return Arguments.parse(arguments, Args.class);
    }

    @Override
    public JsonNode execute(Args arguments) throws ToolFailure {
        boolean created;
        try {
            created = store.write(arguments.name(), arguments.content());
        } catch (IllegalArgumentException e) {
            throw new ToolFailure(e.getMessage(), false);
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("name", arguments.name());
        data.put("written", true);
        data.put("created", created);
        return data;
    }
}
