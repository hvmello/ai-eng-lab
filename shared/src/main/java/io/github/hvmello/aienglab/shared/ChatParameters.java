package io.github.hvmello.aienglab.shared;

/**
 * Sampling and length parameters. A null field is not sent to the provider, which is what lets the same request
 * work on Claude models that reject {@code temperature}.
 */
public record ChatParameters(Double temperature, Double topP, Integer maxOutputTokens) {

    private static final ChatParameters NONE = new ChatParameters(null, null, null);

    public ChatParameters {
        if (temperature != null && temperature < 0) {
            throw new IllegalArgumentException("temperature must not be negative");
        }
        if (topP != null && (topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("topP must be between 0 and 1");
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }

    /** No parameter set: every field is left to the provider default. */
    public static ChatParameters none() { return NONE; }
}
