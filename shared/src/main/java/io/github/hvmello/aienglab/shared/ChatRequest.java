package io.github.hvmello.aienglab.shared;

import java.util.List;
import java.util.Objects;

/**
 * A chat request.
 *
 * @param jsonSchema optional JSON schema (as text) the output must follow, or null for free text
 * @param tools tools the model may call, empty by default; names must be unique
 */
public record ChatRequest(List<Message> messages, ChatParameters parameters, String jsonSchema, List<ToolSpec> tools) {

    public ChatRequest {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        parameters = parameters == null ? ChatParameters.none() : parameters;
        if (jsonSchema != null && jsonSchema.isBlank()) {
            throw new IllegalArgumentException("jsonSchema must be null or non-blank");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (tools.stream().map(ToolSpec::name).distinct().count() != tools.size()) {
            throw new IllegalArgumentException("tool names must be unique");
        }
    }

    public ChatRequest(List<Message> messages, ChatParameters parameters, String jsonSchema) {
        this(messages, parameters, jsonSchema, List.of());
    }

    public static ChatRequest of(Message... messages) {
        return new ChatRequest(List.of(messages), ChatParameters.none(), null, List.of());
    }

    public ChatRequest withParameters(ChatParameters newParameters) {
        return new ChatRequest(messages, newParameters, jsonSchema, tools);
    }

    public ChatRequest withJsonSchema(String schema) {
        return new ChatRequest(messages, parameters, schema, tools);
    }

    public ChatRequest withTools(List<ToolSpec> newTools) {
        return new ChatRequest(messages, parameters, jsonSchema, newTools);
    }

    public ChatRequest withMessages(List<Message> newMessages) {
        return new ChatRequest(newMessages, parameters, jsonSchema, tools);
    }
}
