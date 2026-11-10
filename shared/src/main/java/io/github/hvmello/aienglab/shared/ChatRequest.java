package io.github.hvmello.aienglab.shared;

import java.util.List;
import java.util.Objects;

/**
 * A chat request.
 *
 * @param jsonSchema optional JSON schema (as text) the output must follow, or null for free text
 */
public record ChatRequest(List<Message> messages, ChatParameters parameters, String jsonSchema) {

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
    }

    public static ChatRequest of(Message... messages) {
        return new ChatRequest(List.of(messages), ChatParameters.none(), null);
    }

    public ChatRequest withParameters(ChatParameters newParameters) {
        return new ChatRequest(messages, newParameters, jsonSchema);
    }

    public ChatRequest withJsonSchema(String schema) {
        return new ChatRequest(messages, parameters, schema);
    }
}
