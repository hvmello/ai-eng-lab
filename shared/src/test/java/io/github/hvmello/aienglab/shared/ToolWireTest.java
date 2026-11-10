package io.github.hvmello.aienglab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
 * Tool calling through the real LangChain4j adapters against a local fake server that replays recorded-style
 * provider responses. Proves request shape and response parsing, not live behaviour of any model.
 */
class ToolWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolSpec WEATHER = new ToolSpec(
            "get_weather",
            "Weather for a city",
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}");

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private JsonNode lastBody() throws IOException {
        return JSON.readTree(bodies.get(bodies.size() - 1));
    }

    private static ChatRequest roundTrip(ToolCall call) {
        return ChatRequest.of(
                        Message.user("weather in Porto?"),
                        Message.assistantToolCalls("", List.of(call)),
                        Message.toolResult(call, "{\"ok\":true}"))
                .withTools(List.of(WEATHER));
    }

    @Test
    void ollamaToolRequestAndRecordedToolCallResponse() throws IOException {
        responseBody = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":"
                + "{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Porto\"}}}]},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":5,\"eval_count\":3}";
        var client = LlmClients.ollama(baseUrl(), "m", Duration.ofSeconds(10));

        var result = client.chat(roundTrip(new ToolCall("call_1", "get_weather", "{\"city\":\"Porto\"}")));

        var body = lastBody();
        var tool = body.path("tools").get(0).path("function");
        assertThat(tool.path("name").asText()).isEqualTo("get_weather");
        assertThat(tool.path("parameters").path("required").get(0).asText()).isEqualTo("city");
        var roles = new ArrayList<String>();
        body.path("messages").forEach(m -> roles.add(m.path("role").asText()));
        assertThat(roles).containsExactly("user", "assistant", "tool");
        assertThat(body.path("messages").get(1).path("tool_calls").size()).isEqualTo(1);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(JSON.readTree(result.toolCalls().get(0).argumentsJson()).path("city").asText())
                .isEqualTo("Porto");
        assertThat(result.toolCalls().get(0).id()).isNotBlank();
    }

    @Test
    void anthropicToolRequestAndRecordedToolUseResponse() throws IOException {
        responseBody = "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\","
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\","
                + "\"input\":{\"city\":\"Porto\"}}],"
                + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":30,\"output_tokens\":12}}";
        var client = LlmClients.anthropic(baseUrl() + "/v1/", "k", "claude-test", Duration.ofSeconds(10));

        var result = client.chat(roundTrip(new ToolCall("toolu_0", "get_weather", "{\"city\":\"Lisbon\"}")));

        var body = lastBody();
        var tool = body.path("tools").get(0);
        assertThat(tool.path("name").asText()).isEqualTo("get_weather");
        assertThat(tool.path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(body.path("messages").toString()).contains("tool_use").contains("tool_result").contains("toolu_0");
        assertThat(result.finishReason()).isEqualTo(FinishReason.TOOL_EXECUTION);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).id()).isEqualTo("toolu_1");
        assertThat(JSON.readTree(result.toolCalls().get(0).argumentsJson()).path("city").asText())
                .isEqualTo("Porto");
    }
}
