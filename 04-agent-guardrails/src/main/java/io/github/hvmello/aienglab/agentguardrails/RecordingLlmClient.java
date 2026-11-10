package io.github.hvmello.aienglab.agentguardrails;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.StreamListener;
import io.github.hvmello.aienglab.shared.TokenUsage;
import io.github.hvmello.aienglab.shared.ToolCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps a real client and keeps every answer it gives, so a run can be saved in the replay format that the injection
 * tests read. Recording is a manual step against a real model; CI only replays.
 *
 * <p>File format: {@code {"source","question","notes":{name:content},"turns":[{"text","finishReason","usage",
 * "toolCalls":[{"id","name","arguments"}]}]}}. The test fixtures add {@code description}, {@code approver},
 * {@code budget} and {@code expect}.
 */
public final class RecordingLlmClient implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient delegate;
    private final List<ChatResult> turns = new ArrayList<>();

    public RecordingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        ChatResult result = delegate.chat(request);
        turns.add(result);
        return result;
    }

    @Override
    public void stream(ChatRequest request, StreamListener listener) {
        throw new UnsupportedOperationException("recording does not support streaming");
    }

    public List<ChatResult> turns() {
        return List.copyOf(turns);
    }

    /** Writes what was recorded, with the question and the seeded notes it ran against. */
    public void write(Path file, String question, Map<String, String> notes) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("source", "recorded");
        root.put("question", question);
        ObjectNode noteNode = root.putObject("notes");
        notes.forEach(noteNode::put);
        ArrayNode list = root.putArray("turns");
        turns.forEach(turn -> list.add(turnToJson(turn)));
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    public static ObjectNode turnToJson(ChatResult turn) {
        ObjectNode node = JSON.createObjectNode();
        node.put("text", turn.text() == null ? "" : turn.text());
        node.put("finishReason", turn.finishReason() == null ? null : turn.finishReason().name());
        TokenUsage usage = turn.usage();
        if (usage != null) {
            ObjectNode usageNode = node.putObject("usage");
            usageNode.put("input", usage.inputTokens());
            usageNode.put("output", usage.outputTokens());
            usageNode.put("total", usage.totalTokens());
        }
        ArrayNode calls = node.putArray("toolCalls");
        for (ToolCall call : turn.toolCalls()) {
            ObjectNode callNode = calls.addObject();
            callNode.put("id", call.id());
            callNode.put("name", call.name());
            JsonNode arguments = parseObject(call.argumentsJson());
            if (arguments == null) {
                callNode.put("arguments", call.argumentsJson());
            } else {
                callNode.set("arguments", arguments);
            }
        }
        return node;
    }

    public static ChatResult turnFromJson(JsonNode node) {
        FinishReason reason = node.hasNonNull("finishReason") ? FinishReason.valueOf(node.get("finishReason").asText()) : null;
        TokenUsage usage = null;
        if (node.hasNonNull("usage")) {
            JsonNode u = node.get("usage");
            usage = new TokenUsage(integer(u, "input"), integer(u, "output"), integer(u, "total"));
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : node.path("toolCalls")) {
            JsonNode arguments = call.get("arguments");
            String argumentsJson = arguments == null ? null : arguments.isTextual() ? arguments.asText() : arguments.toString();
            calls.add(new ToolCall(call.get("id").asText(), call.get("name").asText(), argumentsJson));
        }
        return new ChatResult(node.path("text").asText(""), reason, usage, calls);
    }

    private static Integer integer(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    /** The arguments as an object node when the text is a JSON object, otherwise null (kept as text). */
    private static JsonNode parseObject(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
