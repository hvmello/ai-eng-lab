package io.github.hvmello.aienglab.shared;

/** Provider-neutral entry point. Application code depends on this, never on LangChain4j (ADR 0001). */
public interface LlmClient {

    /**
     * Blocking call.
     *
     * <p>When the request lists tools, the model may answer with {@link ChatResult#toolCalls()} instead of text. The
     * client never runs a tool; the caller owns that loop.
     *
     * @throws LlmCallException on transport or provider failure
     */
    ChatResult chat(ChatRequest request);

    /**
     * Streaming call. Failures are reported to the listener, not thrown. Returns when the stream has ended.
     *
     * @throws IllegalArgumentException if the request lists tools: streaming with tools is not supported
     */
    void stream(ChatRequest request, StreamListener listener);
}
