package io.github.hvmello.aienglab.toolcalling;

import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.Message;
import io.github.hvmello.aienglab.shared.StreamListener;
import io.github.hvmello.aienglab.shared.TokenUsage;
import io.github.hvmello.aienglab.shared.ToolCall;
import io.github.hvmello.aienglab.shared.ToolSpec;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The Spring AI adapter behind {@link LlmClient}, over {@code OllamaChatModel}, built by hand with no Spring
 * context. It is the only class in this module that imports {@code org.springframework}.
 *
 * <p>The model's tool calls come back as {@link ChatResult#toolCalls()} and the application loop runs the tools.
 * In Spring AI 2.0.2 {@code OllamaChatModel.call} only returns the model's tool calls; there is no
 * {@code internalToolExecutionEnabled} switch to turn off (it existed in 1.x). The tool callbacks passed to it exist
 * only to carry each tool's schema, and calling one is a bug that fails loudly; a test proves none is called. Spring
 * AI's own retry is set to zero retries, so no hidden retry exists, as {@code maxRetries(0)} does for LangChain4j.
 */
public final class SpringAiLlmClient implements LlmClient {

    private final OllamaChatModel model;
    private final String modelName;

    public SpringAiLlmClient(String baseUrl, String modelName, Duration timeout) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        var api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        this.modelName = modelName;
        this.model = OllamaChatModel.builder()
                .ollamaApi(api)
                .options(OllamaChatOptions.builder().model(modelName).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        try {
            Prompt prompt = new Prompt(messages(request), options(request));
            return toResult(model.call(prompt));
        } catch (RuntimeException e) {
            throw new LlmCallException("Chat call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatRequest request, StreamListener listener) {
        if (!request.tools().isEmpty()) {
            throw new IllegalArgumentException("streaming with tools is not supported");
        }
        listener.onError(new LlmCallException("Streaming is not implemented by the Spring AI adapter", null));
    }

    private OllamaChatOptions options(ChatRequest request) {
        var builder = OllamaChatOptions.builder()
                .model(modelName)
                .toolCallbacks(request.tools().stream().map(SpringAiLlmClient::callbackFor).toList());
        if (request.parameters().temperature() != null) {
            builder.temperature(request.parameters().temperature());
        }
        if (request.parameters().topP() != null) {
            builder.topP(request.parameters().topP());
        }
        if (request.parameters().maxOutputTokens() != null) {
            builder.numPredict(request.parameters().maxOutputTokens());
        }
        if (request.jsonSchema() != null) {
            builder.outputSchema(request.jsonSchema());
        }
        return builder.build();
    }

    private static List<org.springframework.ai.chat.messages.Message> messages(ChatRequest request) {
        List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>();
        for (Message message : request.messages()) {
            switch (message.role()) {
                case SYSTEM -> out.add(new SystemMessage(message.content()));
                case USER -> out.add(new UserMessage(message.content()));
                case ASSISTANT -> out.add(AssistantMessage.builder()
                        .content(message.content())
                        .toolCalls(message.toolCalls().stream()
                                .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.argumentsJson()))
                                .toList())
                        .build());
                case TOOL -> out.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                message.toolCallId(), message.toolName(), message.content())))
                        .build());
            }
        }
        return out;
    }

    private static ChatResult toResult(ChatResponse response) {
        Generation generation = response.getResult();
        if (generation == null) {
            throw new LlmCallException("The model returned no generation", null);
        }
        AssistantMessage output = generation.getOutput();
        List<ToolCall> calls = new ArrayList<>();
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            String id = call.id() == null || call.id().isBlank() ? "call_" + (calls.size() + 1) : call.id();
            calls.add(new ToolCall(id, call.name(), call.arguments()));
        }
        String text = output.getText() == null ? "" : output.getText();
        var usage = response.getMetadata().getUsage();
        return new ChatResult(
                text,
                finishReason(generation, !calls.isEmpty()),
                usage == null
                        ? null
                        : new TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens()),
                calls);
    }

    private static FinishReason finishReason(Generation generation, boolean hasToolCalls) {
        String reason = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
        if (reason != null && reason.equalsIgnoreCase("length")) {
            return FinishReason.LENGTH;
        }
        if (hasToolCalls) {
            return FinishReason.TOOL_EXECUTION;
        }
        if (reason == null) {
            return FinishReason.OTHER;
        }
        return reason.equalsIgnoreCase("stop") ? FinishReason.STOP : FinishReason.OTHER;
    }

    private static ToolCallback callbackFor(ToolSpec spec) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.jsonSchema())
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("Spring AI must not run tools; the application loop does");
            }
        };
    }
}
