package io.github.hvmello.aienglab.shared;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import java.time.Duration;

/**
 * Factories for the supported providers. The blocking models are built with {@code maxRetries(0)}, so no retry
 * happens that the code does not show.
 */
public final class LlmClients {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private LlmClients() {}

    public static LlmClient ollama(String baseUrl, String model) {
        return ollama(baseUrl, model, DEFAULT_TIMEOUT);
    }

    public static LlmClient ollama(String baseUrl, String model, Duration timeout) {
        var chat = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(timeout)
                .maxRetries(0)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .build();
        var streaming = OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(timeout)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .build();
        return new LangChain4jLlmClient(chat, streaming);
    }

    public static LlmClient anthropic(String apiKey, String model) {
        return anthropic(null, apiKey, model, DEFAULT_TIMEOUT);
    }

    /** The base URL is null for the real API, or a local fake in tests. */
    public static LlmClient anthropic(String baseUrl, String apiKey, String model, Duration timeout) {
        var chat = AnthropicChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .timeout(timeout)
                .maxRetries(0)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .build();
        var streaming = AnthropicStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .timeout(timeout)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .build();
        return new LangChain4jLlmClient(chat, streaming);
    }
}
