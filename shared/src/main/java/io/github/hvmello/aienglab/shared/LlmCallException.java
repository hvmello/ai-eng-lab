package io.github.hvmello.aienglab.shared;

/** Transport or provider failure. Bad model output is a different problem and is not reported through this type. */
public class LlmCallException extends RuntimeException {

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
