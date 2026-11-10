package io.github.hvmello.aienglab.shared;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** The only class that translates between this module's types and LangChain4j's. */
final class LangChain4jLlmClient implements LlmClient {

    private static final String SCHEMA_NAME = "response";

    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel;

    LangChain4jLlmClient(ChatModel chatModel, StreamingChatModel streamingModel) {
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        try {
            return toResult(chatModel.chat(toLangChain(request)));
        } catch (RuntimeException e) {
            throw new LlmCallException("Chat call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatRequest request, StreamListener listener) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        AtomicBoolean finished = new AtomicBoolean();
        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (!finished.get()) {
                    listener.onToken(partial);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (finished.compareAndSet(false, true)) {
                    try {
                        listener.onComplete(toResult(response));
                    } finally {
                        done.complete(null);
                    }
                }
            }

            @Override
            public void onError(Throwable error) {
                if (finished.compareAndSet(false, true)) {
                    try {
                        listener.onError(new LlmCallException("Stream failed: " + error.getMessage(), error));
                    } finally {
                        done.complete(null);
                    }
                }
            }
        };
        try {
            streamingModel.chat(toLangChain(request), handler);
        } catch (RuntimeException e) {
            handler.onError(e);
        }
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handler.onError(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    static dev.langchain4j.model.chat.request.ChatRequest toLangChain(ChatRequest request) {
        DefaultChatRequestParameters.Builder<?> parameters = ChatRequestParameters.builder()
                .temperature(request.parameters().temperature())
                .topP(request.parameters().topP())
                .maxOutputTokens(request.parameters().maxOutputTokens());
        if (request.jsonSchema() != null) {
            parameters.responseFormat(ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder()
                            .name(SCHEMA_NAME)
                            .rootElement(JsonRawSchema.from(request.jsonSchema()))
                            .build())
                    .build());
        }
        return dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(request.messages().stream().map(LangChain4jLlmClient::toMessage).toList())
                .parameters(parameters.build())
                .build();
    }

    private static ChatMessage toMessage(Message message) {
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.content());
            case USER -> UserMessage.from(message.content());
            case ASSISTANT -> AiMessage.from(message.content());
        };
    }

    static ChatResult toResult(ChatResponse response) {
        String text = response.aiMessage() == null || response.aiMessage().text() == null
                ? ""
                : response.aiMessage().text();
        var usage = response.tokenUsage();
        return new ChatResult(
                text,
                toFinishReason(response.finishReason()),
                usage == null
                        ? null
                        : new TokenUsage(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount()));
    }

    private static FinishReason toFinishReason(dev.langchain4j.model.output.FinishReason reason) {
        if (reason == null) {
            return FinishReason.OTHER;
        }
        return switch (reason) {
            case STOP -> FinishReason.STOP;
            case LENGTH -> FinishReason.LENGTH;
            case TOOL_EXECUTION -> FinishReason.TOOL_EXECUTION;
            case CONTENT_FILTER -> FinishReason.CONTENT_FILTER;
            case OTHER -> FinishReason.OTHER;
        };
    }
}
