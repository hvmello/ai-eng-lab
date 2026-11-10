package io.github.hvmello.aienglab.shared;

/** Receives a streamed answer. Exactly one of {@code onComplete} or {@code onError} is called, once. */
public interface StreamListener {

    void onToken(String token);

    void onComplete(ChatResult result);

    void onError(LlmCallException error);
}
