package io.github.hvmello.aienglab.toolcalling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * The fixed envelope sent back to the model as the tool message:
 * {@code {"ok":true,"data":...}} or
 * {@code {"ok":false,"error":{"type":...,"message":...,"retryable":...,"fields":[...]}}}.
 * The message tells the model what it can change and never carries stack traces, class names, URLs, hosts,
 * headers or raw upstream bodies.
 */
public record ToolResult(boolean ok, JsonNode data, ErrorType errorType, String message, boolean retryable,
        List<FieldProblem> fields) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum ErrorType {
        UNKNOWN_TOOL("unknown_tool"),
        INVALID_ARGUMENTS("invalid_arguments"),
        TOOL_ERROR("tool_error"),
        TOOL_UNAVAILABLE("tool_unavailable"),
        /** A write was refused by a human or by the deny-all default; produced by module 04, never by this loop. */
        APPROVAL_DENIED("approval_denied");

        private final String wire;

        ErrorType(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public ToolResult {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static ToolResult ok(JsonNode data) {
        return new ToolResult(true, data, null, null, false, List.of());
    }

    public static ToolResult error(ErrorType type, String message, boolean retryable) {
        return new ToolResult(false, null, type, message, retryable, List.of());
    }

    public static ToolResult invalidArguments(List<FieldProblem> fields) {
        return new ToolResult(false, null, ErrorType.INVALID_ARGUMENTS,
                "The arguments do not fit the tool. Fix the listed fields and call it again.", true, fields);
    }

    public String toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("ok", ok);
        if (ok) {
            root.set("data", data == null ? JsonNodeFactory.instance.nullNode() : data);
        } else {
            ObjectNode error = root.putObject("error");
            error.put("type", errorType.wire());
            error.put("message", message);
            error.put("retryable", retryable);
            if (!fields.isEmpty()) {
                ArrayNode list = error.putArray("fields");
                for (FieldProblem field : fields) {
                    list.addObject().put("path", field.path()).put("problem", field.problem());
                }
            }
        }
        return root.toString();
    }

    /** "ok" or the error type of an envelope produced by {@link #toJson}; for traces. */
    public static String statusOf(String envelopeJson) {
        try {
            JsonNode root = JSON.readTree(envelopeJson);
            return root.path("ok").asBoolean() ? "ok" : "error " + root.path("error").path("type").asText("unknown");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "error unreadable";
        }
    }
}
