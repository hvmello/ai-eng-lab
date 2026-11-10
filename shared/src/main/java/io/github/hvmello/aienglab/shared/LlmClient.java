package io.github.hvmello.aienglab.shared;

/** Provider-neutral entry point. Application code depends on this, never on LangChain4j (ADR 0001). */
public interface LlmClient {

    /**
     * Blocking call.
     *
     * @throws LlmCallException on transport or provider failure
     */
    ChatResult chat(ChatRequest request);

    /** Streaming call. Failures are reported to the listener, not thrown. Returns when the stream has ended. */
    void stream(ChatRequest request, StreamListener listener);
}
