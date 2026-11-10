package io.github.hvmello.aienglab.toolcalling;

/**
 * A tool could not do its job. The message goes to the model, so it must say what the model can change or whether
 * trying again may help, and must not carry URLs, hosts, stack traces or upstream bodies.
 */
public class ToolFailure extends Exception {

    private final boolean retryable;

    public ToolFailure(String safeMessage, boolean retryable) {
        super(safeMessage);
        this.retryable = retryable;
    }

    /** True when the same call may succeed later (timeout, overload), false when repeating it is pointless. */
    public boolean retryable() {
        return retryable;
    }
}
