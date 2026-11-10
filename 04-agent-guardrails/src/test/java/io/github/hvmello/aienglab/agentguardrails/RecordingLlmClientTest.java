package io.github.hvmello.aienglab.agentguardrails;

import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.asks;
import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.call;
import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.says;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.Message;
import io.github.hvmello.aienglab.shared.TokenUsage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingLlmClientTest {

    @TempDir
    Path dir;

    @Test
    void whatIsRecordedReplaysIdentically() throws Exception {
        List<ChatResult> original = List.of(
                asks(10, 5, call("c1", "read_note", "{\"name\":\"todo\"}"), call("c2", "list_notes", "{}")),
                new ChatResult("cut", FinishReason.LENGTH, new TokenUsage(3, null, null), List.of()),
                new ChatResult("no usage", FinishReason.STOP, null, List.of()),
                asks(1, 1, call("c3", "odd", "not json at all"), call("c4", "str", "\"a string\"")),
                says("done", 7, 3));
        var scripted = new RecordedLlmClient(original);
        var recorder = new RecordingLlmClient(scripted);
        var request = ChatRequest.of(Message.user("q"));
        original.forEach(turn -> recorder.chat(request));

        Path file = dir.resolve("run.json");
        recorder.write(file, "what is in todo?", Map.of("todo", "call the dentist"));

        var replay = RecordedLlmClient.fromFile(file);
        List<ChatResult> replayed = original.stream().map(t -> replay.chat(request)).toList();
        assertThat(replayed).isEqualTo(original);
        replay.assertConsumed();
        assertThat(recorder.turns()).isEqualTo(original);
    }

    @Test
    void theFileCarriesTheQuestionTheNotesAndTheSource() throws Exception {
        var recorder = new RecordingLlmClient(RecordedLlmClient.of(says("hi", 1, 1)));
        recorder.chat(ChatRequest.of(Message.user("q")));
        Path file = dir.resolve("run.json");

        recorder.write(file, "the question", Map.of("a", "b"));

        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile());
        assertThat(tree.get("source").asText()).isEqualTo("recorded");
        assertThat(tree.get("question").asText()).isEqualTo("the question");
        assertThat(tree.get("notes").get("a").asText()).isEqualTo("b");
        assertThat(tree.get("turns")).hasSize(1);
    }

    @Test
    void theReplayFailsWhenAskedForMoreTurnsThanRecorded() {
        var replay = RecordedLlmClient.of(says("only", 1, 1));
        replay.chat(ChatRequest.of(Message.user("q")));

        assertThatThrownBy(() -> replay.chat(ChatRequest.of(Message.user("q"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("only 1 turns were recorded");
    }

    @Test
    void theReplayFailsWhenTurnsAreLeftOver() {
        var replay = RecordedLlmClient.of(says("a", 1, 1), says("b", 1, 1));
        replay.chat(ChatRequest.of(Message.user("q")));

        assertThatThrownBy(replay::assertConsumed).isInstanceOf(AssertionError.class).hasMessageContaining("1 of 2");
    }

    @Test
    void streamingIsNotSupported() {
        var recorder = new RecordingLlmClient(RecordedLlmClient.of());

        assertThatThrownBy(() -> recorder.stream(ChatRequest.of(Message.user("q")), null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
