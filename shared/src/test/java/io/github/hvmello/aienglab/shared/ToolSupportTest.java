package io.github.hvmello.aienglab.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Tool support in the adapter, against stub LangChain4j models: no network, no model. */
class ToolSupportTest {

    private static final String WEATHER_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"city\":{\"type\":\"string\",\"description\":\"City name\"},"
            + "\"days\":{\"type\":\"integer\"},"
            + "\"unit\":{\"type\":\"string\",\"enum\":[\"c\",\"f\"]}},"
            + "\"required\":[\"city\"]}";

    private static final class StubChatModel implements ChatModel {
        final List<dev.langchain4j.model.chat.request.ChatRequest> seen = new ArrayList<>();
        final Function<dev.langchain4j.model.chat.request.ChatRequest, ChatResponse> answer;

        StubChatModel(Function<dev.langchain4j.model.chat.request.ChatRequest, ChatResponse> answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
            seen.add(request);
            return answer.apply(request);
        }
    }

    private static final class NoopStreamingModel implements StreamingChatModel {
        @Override
        public void doChat(
                dev.langchain4j.model.chat.request.ChatRequest request, StreamingChatResponseHandler handler) {}
    }

    private static final class NoopListener implements StreamListener {
        @Override
        public void onToken(String token) {}

        @Override
        public void onComplete(ChatResult result) {}

        @Override
        public void onError(LlmCallException error) {}
    }

    private static ChatResponse toolRequests(ToolExecutionRequest... requests) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(requests))
                .finishReason(dev.langchain4j.model.output.FinishReason.TOOL_EXECUTION)
                .build();
    }

    @Test
    void toolSpecsAreMappedToLangChainSpecifications() {
        var request = ChatRequest.of(Message.user("weather?"))
                .withTools(List.of(new ToolSpec("get_weather", "Weather for a city", WEATHER_SCHEMA)));

        var mapped = LangChain4jLlmClient.toLangChain(request);

        assertThat(mapped.toolSpecifications()).hasSize(1);
        var spec = mapped.toolSpecifications().get(0);
        assertThat(spec.name()).isEqualTo("get_weather");
        assertThat(spec.description()).isEqualTo("Weather for a city");
        assertThat(spec.parameters().properties()).containsOnlyKeys("city", "days", "unit");
        assertThat(spec.parameters().required()).containsExactly("city");
    }

    @Test
    void aRequestWithoutToolsSendsNone() {
        var mapped = LangChain4jLlmClient.toLangChain(ChatRequest.of(Message.user("hi")));

        assertThat(mapped.toolSpecifications()).isNullOrEmpty();
    }

    @Test
    void invalidToolSchemasAreRejected() {
        var notObject = ChatRequest.of(Message.user("hi"))
                .withTools(List.of(new ToolSpec("t", "d", "{\"type\":\"string\"}")));
        var notJson = ChatRequest.of(Message.user("hi")).withTools(List.of(new ToolSpec("t", "d", "{oops")));
        var unsupported = ChatRequest.of(Message.user("hi"))
                .withTools(List.of(new ToolSpec(
                        "t", "d", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"null\"}}}")));

        assertThatThrownBy(() -> LangChain4jLlmClient.toLangChain(notObject))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LangChain4jLlmClient.toLangChain(notJson))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LangChain4jLlmClient.toLangChain(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
    }

    @Test
    void toolCallsInTheAnswerAreMapped() {
        var chat = new StubChatModel(r -> toolRequests(ToolExecutionRequest.builder()
                .id("c1")
                .name("get_weather")
                .arguments("{\"city\":\"Porto\"}")
                .build()));
        var client = new LangChain4jLlmClient(chat, new NoopStreamingModel());

        var result = client.chat(ChatRequest.of(Message.user("weather?")));

        assertThat(result.toolCalls()).containsExactly(new ToolCall("c1", "get_weather", "{\"city\":\"Porto\"}"));
        assertThat(result.finishReason()).isEqualTo(FinishReason.TOOL_EXECUTION);
        assertThat(result.text()).isEmpty();
    }

    @Test
    void aMissingCallIdGetsAStableOne() {
        var chat = new StubChatModel(r -> toolRequests(
                ToolExecutionRequest.builder().name("a").arguments("{}").build(),
                ToolExecutionRequest.builder().name("b").arguments("{}").build()));

        var result = new LangChain4jLlmClient(chat, new NoopStreamingModel()).chat(ChatRequest.of(Message.user("x")));

        assertThat(result.toolCalls()).extracting(ToolCall::id).containsExactly("call_1", "call_2");
    }

    @Test
    void toolRoundTripMessagesAreMapped() {
        var call = new ToolCall("c1", "get_weather", "{\"city\":\"Porto\"}");
        var chat = new StubChatModel(r -> ChatResponse.builder()
                .aiMessage(AiMessage.from("sunny"))
                .finishReason(dev.langchain4j.model.output.FinishReason.STOP)
                .build());
        var client = new LangChain4jLlmClient(chat, new NoopStreamingModel());

        client.chat(ChatRequest.of(
                Message.user("weather?"),
                Message.assistantToolCalls("", List.of(call)),
                Message.toolResult(call, "{\"ok\":true}")));

        var sent = chat.seen.get(0).messages();
        var assistant = (AiMessage) sent.get(1);
        assertThat(assistant.toolExecutionRequests()).hasSize(1);
        assertThat(assistant.toolExecutionRequests().get(0).id()).isEqualTo("c1");
        assertThat(assistant.toolExecutionRequests().get(0).arguments()).isEqualTo("{\"city\":\"Porto\"}");
        var tool = (ToolExecutionResultMessage) sent.get(2);
        assertThat(tool.id()).isEqualTo("c1");
        assertThat(tool.toolName()).isEqualTo("get_weather");
        assertThat(tool.text()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void streamingWithToolsIsRejected() {
        var client = new LangChain4jLlmClient(new StubChatModel(r -> null), new NoopStreamingModel());
        var request = ChatRequest.of(Message.user("hi")).withTools(List.of(new ToolSpec("t", "d", WEATHER_SCHEMA)));

        assertThatThrownBy(() -> client.stream(request, new NoopListener()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tools");
    }

    @Test
    void toolMessagesAreValidatedPerRole() {
        var call = new ToolCall("c1", "t", "{}");
        var spec = new ToolSpec("t", "d", WEATHER_SCHEMA);

        assertThatThrownBy(() -> new Message(Message.Role.TOOL, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Message(Message.Role.USER, "x", List.of(call), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Message(Message.Role.USER, "x", List.of(), "c1", "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Message.assistantToolCalls("", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatRequest.of(Message.user("x")).withTools(List.of(spec, spec)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolSpec("bad name", "d", WEATHER_SCHEMA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ToolCall("c", "t", null).argumentsJson()).isEqualTo("{}");
    }
}
