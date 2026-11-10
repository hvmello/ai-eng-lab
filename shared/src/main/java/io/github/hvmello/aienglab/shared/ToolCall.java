package io.github.hvmello.aienglab.shared;

import java.util.Objects;

/**
 * A tool call requested by the model. {@code argumentsJson} is always JSON text, whatever shape the provider used
 * on the wire (an object, or a string holding an object). It is untrusted input.
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }
}
