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
 * The real LangChain4j adapters against a local fake HTTP server that replays recorded provider responses. Proves
 * the request shape and the response parsing, not live behaviour of any model.
 */
class WireLevelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"vendor\":{\"type\":\"string\"}},\"required\":[\"vendor\"]}";

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    private volatile String contentType = "application/json";
    private volatile String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String key = exchange.getRequestHeaders().getFirst("x-api-key");
            apiKeys.add(key == null ? "" : key);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
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

    @Test
    void ollamaChatResponseParses() throws IOException {
        responseBody = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello there\"},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":11,\"eval_count\":7}";
        var client = LlmClients.ollama(baseUrl(), "m", Duration.ofSeconds(10));

        var result = client.chat(ChatRequest.of(Message.user("hi")));

        assertThat(paths).containsExactly("/api/chat");
        assertThat(result.text()).isEqualTo("Hello there");
        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage()).isEqualTo(new TokenUsage(11, 7, 18));
        assertThat(lastBody().path("model").asText()).isEqualTo("m");
        assertThat(lastBody().path("stream").asBoolean()).isFalse();
    }

    @Test
    void ollamaTruncatedAnswerReportsLength() {
        responseBody = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"vendor\\\":\"},"
                + "\"done\":true,\"done_reason\":\"length\",\"prompt_eval_count\":11,\"eval_count\":5}";
        var client = LlmClients.ollama(baseUrl(), "m", Duration.ofSeconds(10));

        var result = client.chat(ChatRequest.of(Message.user("hi")));

        assertThat(result.finishReason()).isEqualTo(FinishReason.LENGTH);
    }

    @Test
    void ollamaStreamingResponseParses() {
        contentType = "application/x-ndjson";
        responseBody = chunk("Hel", false) + chunk("lo", false)
                + "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":4,\"eval_count\":2}\n";
        var client = LlmClients.ollama(baseUrl(), "m", Duration.ofSeconds(10));
        var tokens = new ArrayList<String>();
        var results = new ArrayList<ChatResult>();
        var errors = new ArrayList<LlmCallException>();

        client.stream(ChatRequest.of(Message.user("hi")), new StreamListener() {
            @Override
            public void onToken(String token) {
                tokens.add(token);
            }

            @Override
            public void onComplete(ChatResult result) {
                results.add(result);
            }

            @Override
            public void onError(LlmCallException error) {
                errors.add(error);
            }
        });

        assertThat(errors).isEmpty();
        assertThat(String.join("", tokens)).isEqualTo("Hello");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).text()).isEqualTo("Hello");
        assertThat(results.get(0).finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void ollamaRequestCarriesTheSchemaAndOnlyTheSetParameters() throws IOException {
        responseBody = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"done\":true,\"done_reason\":\"stop\"}";
        var client = LlmClients.ollama(baseUrl(), "m", Duration.ofSeconds(10));

        client.chat(ChatRequest.of(Message.user("hi")).withJsonSchema(SCHEMA));

        var body = lastBody();
        assertThat(body.path("format").path("type").asText()).isEqualTo("object");
        assertThat(body.path("format").path("required").get(0).asText()).isEqualTo("vendor");
        assertThat(body.path("options").has("temperature")).isFalse();
    }

    @Test
    void anthropicRequestCarriesTheOutputFormatAndOmitsTemperatureWhenUnset() throws IOException {
        responseBody = anthropicMessage("{\\\"vendor\\\":\\\"ACME\\\"}", "end_turn");
        var client = LlmClients.anthropic(baseUrl() + "/v1/", "test-key", "claude-test", Duration.ofSeconds(10));

        var result = client.chat(ChatRequest.of(Message.system("extract"), Message.user("ACME invoice"))
                .withJsonSchema(SCHEMA));

        assertThat(paths).containsExactly("/v1/messages");
        assertThat(apiKeys).containsExactly("test-key");
        var body = lastBody();
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(body.path("model").asText()).isEqualTo("claude-test");
        assertThat(body.path("system").toString()).contains("extract");
        var format = body.path("output_config").path("format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("schema").path("required").get(0).asText()).isEqualTo("vendor");
        assertThat(result.text()).isEqualTo("{\"vendor\":\"ACME\"}");
        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage()).isEqualTo(new TokenUsage(21, 9, 30));
    }

    @Test
    void anthropicMaxTokensStopMapsToLength() {
        responseBody = anthropicMessage("cut", "max_tokens");
        var client = LlmClients.anthropic(baseUrl() + "/v1/", "k", "claude-test", Duration.ofSeconds(10));

        var result = client.chat(ChatRequest.of(Message.user("hi")));

        assertThat(result.finishReason()).isEqualTo(FinishReason.LENGTH);
    }

    @Test
    void anthropicSendsTemperatureOnlyWhenSet() throws IOException {
        responseBody = anthropicMessage("ok", "end_turn");
        var client = LlmClients.anthropic(baseUrl() + "/v1/", "k", "claude-test", Duration.ofSeconds(10));

        client.chat(ChatRequest.of(Message.user("hi")).withParameters(new ChatParameters(0.3, null, 50)));

        assertThat(lastBody().path("temperature").asDouble()).isEqualTo(0.3);
        assertThat(lastBody().path("max_tokens").asInt()).isEqualTo(50);
    }

    private static String chunk(String content, boolean done) {
        return "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + content + "\"},\"done\":" + done + "}\n";
    }

    private static String anthropicMessage(String escapedText, String stopReason) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + escapedText + "\"}],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":21,\"output_tokens\":9}}";
    }
}
