package io.github.hvmello.aienglab.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The adapter against stub LangChain4j models: nothing here talks to a network or a model. */
class LangChain4jLlmClientTest {

    /** Records the outgoing request and answers with whatever the function returns, or throws. */
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

    private static final class StubStreamingModel implements StreamingChatModel {
        final List<dev.langchain4j.model.chat.request.ChatRequest> seen = new ArrayList<>();
        final BiConsumer<dev.langchain4j.model.chat.request.ChatRequest, StreamingChatResponseHandler> script;

        StubStreamingModel(
                BiConsumer<dev.langchain4j.model.chat.request.ChatRequest, StreamingChatResponseHandler> script) {
            this.script = script;
        }

        @Override
        public void doChat(
                dev.langchain4j.model.chat.request.ChatRequest request, StreamingChatResponseHandler handler) {
            seen.add(request);
            script.accept(request, handler);
        }
    }

    private static ChatResponse response(String text, dev.langchain4j.model.output.FinishReason reason) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .finishReason(reason)
                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(11, 7, 18))
                .build();
    }

    private static final class RecordingListener implements StreamListener {
        final List<String> events = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        ChatResult result;
        LlmCallException error;

        @Override
        public void onToken(String token) {
            tokens.add(token);
            events.add("token:" + token);
        }

        @Override
        public void onComplete(ChatResult chatResult) {
            result = chatResult;
            events.add("complete");
        }

        @Override
        public void onError(LlmCallException exception) {
            error = exception;
            events.add("error");
        }
    }

    private static LangChain4jLlmClient clientFor(StubChatModel chat) {
        return new LangChain4jLlmClient(chat, new StubStreamingModel((r, h) -> {}));
    }

    @Test
    void parametersMapThrough() {
        var chat = new StubChatModel(r -> response("ok", dev.langchain4j.model.output.FinishReason.STOP));
        var request = ChatRequest.of(Message.system("be brief"), Message.user("hi"))
                .withParameters(new ChatParameters(0.2, 0.9, 123));

        clientFor(chat).chat(request);

        var sent = chat.seen.get(0);
        assertThat(sent.temperature()).isEqualTo(0.2);
        assertThat(sent.topP()).isEqualTo(0.9);
        assertThat(sent.maxOutputTokens()).isEqualTo(123);
        assertThat(sent.messages()).hasSize(2);
        assertThat(sent.messages().get(0).type().name()).isEqualTo("SYSTEM");
        assertThat(sent.messages().get(1).type().name()).isEqualTo("USER");
    }

    @Test
    void unsetTemperatureIsAbsentFromTheOutgoingRequest() {
        var chat = new StubChatModel(r -> response("ok", dev.langchain4j.model.output.FinishReason.STOP));

        clientFor(chat).chat(ChatRequest.of(Message.user("hi")));

        var sent = chat.seen.get(0);
        assertThat(sent.temperature()).isNull();
        assertThat(sent.topP()).isNull();
        assertThat(sent.maxOutputTokens()).isNull();
    }

    @Test
    void schemaBecomesAJsonResponseFormat() {
        var chat = new StubChatModel(r -> response("{}", dev.langchain4j.model.output.FinishReason.STOP));
        var schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";

        clientFor(chat).chat(ChatRequest.of(Message.user("hi")).withJsonSchema(schema));

        var format = chat.seen.get(0).responseFormat();
        assertThat(format.type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(((JsonRawSchema) format.jsonSchema().rootElement()).schema()).isEqualTo(schema);
    }

    @Test
    void noSchemaMeansNoResponseFormat() {
        var chat = new StubChatModel(r -> response("hi", dev.langchain4j.model.output.FinishReason.STOP));

        clientFor(chat).chat(ChatRequest.of(Message.user("hi")));

        assertThat(chat.seen.get(0).responseFormat()).isNull();
    }

    @Test
    void finishReasonAndUsageMapThrough() {
        var chat = new StubChatModel(r -> response("cut", dev.langchain4j.model.output.FinishReason.LENGTH));

        var result = clientFor(chat).chat(ChatRequest.of(Message.user("hi")));

        assertThat(result.text()).isEqualTo("cut");
        assertThat(result.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(result.usage()).isEqualTo(new TokenUsage(11, 7, 18));
    }

    @Test
    void missingFinishReasonBecomesOther() {
        var chat = new StubChatModel(r -> ChatResponse.builder().aiMessage(AiMessage.from("x")).build());

        var result = clientFor(chat).chat(ChatRequest.of(Message.user("hi")));

        assertThat(result.finishReason()).isEqualTo(FinishReason.OTHER);
        assertThat(result.usage()).isNull();
    }

    @Test
    void providerExceptionsBecomeLlmCallException() {
        var boom = new IllegalStateException("connection refused");
        var chat = new StubChatModel(r -> {
            throw boom;
        });

        assertThatThrownBy(() -> clientFor(chat).chat(ChatRequest.of(Message.user("hi"))))
                .isInstanceOf(LlmCallException.class)
                .hasCause(boom)
                .hasMessageContaining("connection refused");
    }

    @Test
    void streamingDeliversTokensInOrderAndCompletesOnce() {
        var streaming = new StubStreamingModel((r, h) -> {
            h.onPartialResponse("Hel");
            h.onPartialResponse("lo");
            h.onCompleteResponse(response("Hello", dev.langchain4j.model.output.FinishReason.STOP));
        });
        var listener = new RecordingListener();

        new LangChain4jLlmClient(new StubChatModel(r -> null), streaming)
                .stream(ChatRequest.of(Message.user("hi")), listener);

        assertThat(listener.events).containsExactly("token:Hel", "token:lo", "complete");
        assertThat(listener.result.text()).isEqualTo("Hello");
        assertThat(listener.result.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void midStreamFailureCallsOnErrorOnceAndNeverOnComplete() {
        var streaming = new StubStreamingModel((r, h) -> {
            h.onPartialResponse("par");
            h.onError(new RuntimeException("socket closed"));
            h.onError(new RuntimeException("second failure"));
            h.onPartialResponse("late");
            h.onCompleteResponse(response("par", dev.langchain4j.model.output.FinishReason.STOP));
        });
        var listener = new RecordingListener();

        new LangChain4jLlmClient(new StubChatModel(r -> null), streaming)
                .stream(ChatRequest.of(Message.user("hi")), listener);

        assertThat(listener.events).containsExactly("token:par", "error");
        assertThat(listener.error).hasMessageContaining("socket closed");
        assertThat(listener.result).isNull();
    }

    @Test
    void failureWhileStartingTheStreamIsReportedToTheListener() {
        var streaming = new StubStreamingModel((r, h) -> {
            throw new IllegalStateException("cannot connect");
        });
        var listener = new RecordingListener();

        new LangChain4jLlmClient(new StubChatModel(r -> null), streaming)
                .stream(ChatRequest.of(Message.user("hi")), listener);

        assertThat(listener.events).containsExactly("error");
    }

    @Test
    void streamingWorksWhenTheModelAnswersFromAnotherThread() {
        var streaming = new StubStreamingModel((r, h) -> new Thread(() -> {
                    h.onPartialResponse("a");
                    h.onCompleteResponse(response("a", dev.langchain4j.model.output.FinishReason.STOP));
                })
                .start());
        var listener = new RecordingListener();

        new LangChain4jLlmClient(new StubChatModel(r -> null), streaming)
                .stream(ChatRequest.of(Message.user("hi")), listener);

        assertThat(listener.events).containsExactly("token:a", "complete");
    }

    @Test
    void requestValidationRejectsBadInput() {
        assertThatThrownBy(() -> new ChatParameters(null, 1.5, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatParameters(-1.0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatParameters(null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatRequest(List.of(), null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
