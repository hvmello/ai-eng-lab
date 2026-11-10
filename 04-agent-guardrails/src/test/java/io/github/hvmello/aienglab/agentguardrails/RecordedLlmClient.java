package io.github.hvmello.aienglab.agentguardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Replays recorded model turns in order. It fails when the code under test asks for more turns than were recorded,
 * and {@link #assertConsumed()} fails when some were never asked for, so a stale recording cannot pass quietly.
 */
final class RecordedLlmClient implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    final List<ChatRequest> requests = new ArrayList<>();
    private final List<ChatResult> turns;

    RecordedLlmClient(List<ChatResult> turns) {
        this.turns = List.copyOf(turns);
    }

    static RecordedLlmClient of(ChatResult... turns) {
        return new RecordedLlmClient(List.of(turns));
    }

    static RecordedLlmClient fromJson(JsonNode turnsNode) {
        List<ChatResult> turns = new ArrayList<>();
        turnsNode.forEach(node -> turns.add(RecordingLlmClient.turnFromJson(node)));
        return new RecordedLlmClient(turns);
    }

    static RecordedLlmClient fromFile(Path file) throws IOException {
        return fromJson(JSON.readTree(Files.readString(file)).get("turns"));
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        if (requests.size() >= turns.size()) {
            throw new AssertionError("the code asked for model call " + (requests.size() + 1) + " but only "
                    + turns.size() + " turns were recorded");
        }
        requests.add(request);
        return turns.get(requests.size() - 1);
    }

    @Override
    public void stream(ChatRequest request, StreamListener listener) {
        throw new UnsupportedOperationException();
    }

    void assertConsumed() {
        if (requests.size() != turns.size()) {
            throw new AssertionError("only " + requests.size() + " of " + turns.size() + " recorded turns were used");
        }
    }

    // Builders for scripted turns.

    static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json);
    }

    static ChatResult asks(int in, int out, ToolCall... calls) {
        return new ChatResult("", FinishReason.TOOL_EXECUTION, new TokenUsage(in, out, in + out), List.of(calls));
    }

    static ChatResult says(String text, int in, int out) {
        return new ChatResult(text, FinishReason.STOP, new TokenUsage(in, out, in + out), List.of());
    }
}
