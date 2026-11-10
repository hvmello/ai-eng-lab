package io.github.hvmello.aienglab.llmbasics;

/**
 * Why an extraction failed. Every subtype keeps the raw model output, so a caller can log it, show it or retry
 * with it. Provider and transport failures are not here: they surface as {@code LlmCallException}.
 */
public abstract sealed class ExtractionException extends RuntimeException {

    private final String rawOutput;

    private ExtractionException(String message, String rawOutput, Throwable cause) {
        super(message, cause);
        this.rawOutput = rawOutput;
    }

    /** The exact text the model returned. */
    public String rawOutput() {
        return rawOutput;
    }

    /** The model stopped because it hit the output limit, so the text is incomplete. */
    public static final class TruncatedOutput extends ExtractionException {
        public TruncatedOutput(String rawOutput) {
            super("Model output was cut off by the token limit", rawOutput, null);
        }
    }

    /** The text is empty or is not JSON. */
    public static final class MalformedJson extends ExtractionException {
        public MalformedJson(String message, String rawOutput, Throwable cause) {
            super(message, rawOutput, cause);
        }
    }

    /** Valid JSON with the wrong shape: missing or null field, unknown field, wrong type, unparseable date. */
    public static final class SchemaMismatch extends ExtractionException {
        public SchemaMismatch(String message, String rawOutput, Throwable cause) {
            super(message, rawOutput, cause);
        }
    }

    /** The right shape with a value that breaks a rule: non-positive amount, bad currency, totals that disagree. */
    public static final class InvalidValue extends ExtractionException {
        public InvalidValue(String message, String rawOutput) {
            super(message, rawOutput, null);
        }
    }
}
