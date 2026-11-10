package io.github.hvmello.aienglab.toolcalling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.Message;
import io.github.hvmello.aienglab.shared.StreamListener;
import io.github.hvmello.aienglab.shared.TokenUsage;
import io.github.hvmello.aienglab.shared.ToolCall;
import io.github.hvmello.aienglab.shared.ToolSpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Spring AI adapter against a fake Ollama server replaying recorded-style responses. Proves request shape and
 * response parsing, and that Spring AI neither runs tools nor retries; it does not prove live model behaviour.
 */
class SpringAiLlmClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolSpec WEATHER = new ToolSpec(
            "get_forecast",
            "Forecast for a coordinate",
            "{\"type\":\"object\",\"properties\":{\"latitude\":{\"type\":\"number\"},"
                    + "\"longitude\":{\"type\":\"number\"}},\"required\":[\"latitude\",\"longitude\"]}");

    private static final String TOOL_CALL_RESPONSE = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":"
            + "{\"name\":\"get_forecast\",\"arguments\":{\"latitude\":-30.03,\"longitude\":-51.23}}}]},"
            + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":9,\"eval_count\":4}";

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SpringAiLlmClient client() {
        return new SpringAiLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "m", Duration.ofSeconds(10));
    }

    private JsonNode lastBody() throws IOException {
        return JSON.readTree(bodies.get(bodies.size() - 1));
    }

    @Test
    void aRecordedToolCallComesBackAsToolCallsAndNoToolCallbackRuns() throws IOException {
        responseBody = TOOL_CALL_RESPONSE;

        ChatResult result = client().chat(
                ChatRequest.of(Message.user("Will it rain in Porto Alegre?")).withTools(List.of(WEATHER)));

        // One request only: Spring AI did not run the tool and call the model again.
        assertThat(bodies).hasSize(1);
        assertThat(result.toolCalls()).hasSize(1);
        ToolCall call = result.toolCalls().get(0);
        assertThat(call.name()).isEqualTo("get_forecast");
        assertThat(call.id()).isNotBlank();
        JsonNode arguments = JSON.readTree(call.argumentsJson());
        assertThat(arguments.path("latitude").asDouble()).isEqualTo(-30.03);
        assertThat(arguments.path("longitude").asDouble()).isEqualTo(-51.23);
        assertThat(result.finishReason()).isEqualTo(FinishReason.TOOL_EXECUTION);
        assertThat(result.usage()).isEqualTo(new TokenUsage(9, 4, 13));
    }

    @Test
    void theRequestCarriesTheToolSchemaAndTheToolRoundTrip() throws IOException {
        responseBody = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Rain is likely.\"},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":20,\"eval_count\":5}";
        var call = new ToolCall("call_1", "get_forecast", "{\"latitude\":-30.03,\"longitude\":-51.23}");

        ChatResult result = client().chat(ChatRequest.of(
                        Message.system("Be brief."),
                        Message.user("Will it rain?"),
                        Message.assistantToolCalls("", List.of(call)),
                        Message.toolResult(call, "{\"ok\":true,\"data\":{}}"))
                .withTools(List.of(WEATHER)));

        JsonNode body = lastBody();
        JsonNode function = body.path("tools").get(0).path("function");
        assertThat(function.path("name").asText()).isEqualTo("get_forecast");
        assertThat(function.path("parameters").path("required").toString()).contains("latitude").contains("longitude");
        List<String> roles = new ArrayList<>();
        body.path("messages").forEach(m -> roles.add(m.path("role").asText()));
        assertThat(roles).containsExactly("system", "user", "assistant", "tool");
        assertThat(body.path("messages").get(2).path("tool_calls").get(0).path("function").path("name").asText())
                .isEqualTo("get_forecast");
        assertThat(body.path("messages").get(3).path("content").asText()).contains("\"ok\":true");
        assertThat(result.text()).isEqualTo("Rain is likely.");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void aServerErrorIsOneRequestAndAnLlmCallException() {
        status = 500;
        responseBody = "{\"error\":\"overloaded\"}";

        assertThatThrownBy(() -> client().chat(ChatRequest.of(Message.user("hi"))))
                .isInstanceOf(LlmCallException.class);
        assertThat(bodies).hasSize(1);
    }

    @Test
    void aLengthFinishIsReportedAsLength() {
        responseBody = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"cut\"},\"done\":true,\"done_reason\":\"length\",\"prompt_eval_count\":5,\"eval_count\":5}";

        assertThat(client().chat(ChatRequest.of(Message.user("hi"))).finishReason()).isEqualTo(FinishReason.LENGTH);
    }

    @Test
    void streamingIsRejectedWithToolsAndReportedWithout() {
        var request = ChatRequest.of(Message.user("hi"));
        var errors = new ArrayList<LlmCallException>();
        StreamListener listener = new StreamListener() {
            @Override
            public void onToken(String token) {}

            @Override
            public void onComplete(ChatResult result) {}

            @Override
            public void onError(LlmCallException error) {
                errors.add(error);
            }
        };

        assertThatThrownBy(() -> client().stream(request.withTools(List.of(WEATHER)), listener))
                .isInstanceOf(IllegalArgumentException.class);
        client().stream(request, listener);

        assertThat(errors).hasSize(1);
        assertThat(bodies).isEmpty();
    }
}
