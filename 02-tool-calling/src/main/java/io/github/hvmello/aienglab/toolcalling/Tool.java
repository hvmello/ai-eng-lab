package io.github.hvmello.aienglab.toolcalling;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hvmello.aienglab.shared.ToolSpec;

/**
 * A Java tool the model may call. The application validates: {@link #parse} turns untrusted JSON into a typed
 * argument or throws {@link InvalidArgumentsException}; the schema in {@link #spec} is only a hint to the model.
 *
 * @param <A> the typed arguments
 */
public interface Tool<A> {

    ToolSpec spec();

    /** Validates and converts the arguments. Never trusts the model to have followed the schema. */
    A parse(JsonNode arguments);

    /** Runs the tool. Returns only the data the model needs. */
    JsonNode execute(A arguments) throws ToolFailure;
}
