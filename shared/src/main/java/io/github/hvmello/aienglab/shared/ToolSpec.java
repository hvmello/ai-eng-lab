package io.github.hvmello.aienglab.shared;

import java.util.Objects;

/**
 * A tool the model may request. {@code jsonSchema} describes the arguments as a JSON object schema and is a hint to
 * the model, not a guarantee: the application must validate whatever comes back.
 */
public record ToolSpec(String name, String description, String jsonSchema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("tool name must match [A-Za-z0-9_-]{1,64}: " + name);
        }
        if (jsonSchema.isBlank()) {
            throw new IllegalArgumentException("jsonSchema must not be blank");
        }
    }
}
