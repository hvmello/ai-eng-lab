package io.github.hvmello.aienglab.agentguardrails;

import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.asks;
import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.call;
import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.says;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.TokenUsage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private int run(RecordedLlmClient client, Approver approver, Budget budget, String... args) {
        return Main.run(args, () -> client, approver, budget, out, err);
    }

    private static RecordedLlmClient readThenWriteThenAnswer() {
        return RecordedLlmClient.of(
                asks(10, 5, call("c1", "read_note", "{\"name\":\"shopping\"}")),
                asks(10, 5, call("c2", "write_note", "{\"name\":\"shopping\",\"content\":\"TOP SECRET BODY\"}")),
                says("All done.", 10, 5));
    }

    @Test
    void withoutACommandItPrintsUsageAndExitsTwo() {
        int code = run(RecordedLlmClient.of(), Approver.denyAll(), Budget.defaults());

        assertThat(code).isEqualTo(2);
        assertThat(err()).contains("Missing command").contains("Usage:");
    }

    @Test
    void badArgumentsExitTwo() {
        var client = RecordedLlmClient.of();

        assertThat(run(client, Approver.denyAll(), Budget.defaults(), "ask")).isEqualTo(2);
        assertThat(run(client, Approver.denyAll(), Budget.defaults(), "ask", "q", "--approve", "maybe")).isEqualTo(2);
        assertThat(run(client, Approver.denyAll(), Budget.defaults(), "ask", "q", "--approve")).isEqualTo(2);
        assertThat(run(client, Approver.denyAll(), Budget.defaults(), "ask", "q", "--record")).isEqualTo(2);
        assertThat(run(client, Approver.denyAll(), Budget.defaults(), "nope")).isEqualTo(2);
        assertThat(err()).contains("Unknown --approve value: maybe").contains("--record needs a value");
    }

    @Test
    void writesAreDeniedByDefaultAndTheTraceSaysSo() {
        var approver = StubApprover.approving();

        int code = run(readThenWriteThenAnswer(), approver, Budget.defaults(), "ask", "update", "my", "list");

        assertThat(code).isEqualTo(0);
        assertThat(approver.requests).isEmpty();
        assertThat(out()).contains("[step 1] read_note -> ok")
                .contains("[step 2] write_note -> denied")
                .contains("All done.")
                .contains("Spend: steps=3 toolCalls=2 tokens=45 costUsd=0");
    }

    @Test
    void approvePromptUsesTheApprover() {
        var approver = StubApprover.approving();

        int code = run(readThenWriteThenAnswer(), approver, Budget.defaults(), "ask", "q", "--approve", "prompt");

        assertThat(code).isEqualTo(0);
        assertThat(approver.requests).hasSize(1);
        assertThat(out()).contains("[step 2] write_note -> ok");
    }

    @Test
    void approveDenyNeverAsks() {
        var approver = StubApprover.approving();

        run(readThenWriteThenAnswer(), approver, Budget.defaults(), "ask", "q", "--approve", "deny");

        assertThat(approver.requests).isEmpty();
        assertThat(out()).contains("write_note -> denied");
    }

    @Test
    void noNoteBodyAndNoArgumentIsEverPrinted() {
        run(readThenWriteThenAnswer(), StubApprover.approving(), Budget.defaults(), "ask", "q", "--approve", "prompt");

        assertThat(out() + err()).doesNotContain("milk").doesNotContain("TOP SECRET BODY").doesNotContain("eggs");
    }

    @Test
    void aBudgetStopIsReportedWithItsReasonAndExitsOne() {
        var client = RecordedLlmClient.of(asks(60, 50, call("c1", "list_notes", "{}")));

        int code = run(client, Approver.denyAll(), Budget.defaults().withMaxTotalTokens(100), "ask", "q");

        assertThat(code).isEqualTo(1);
        assertThat(err()).contains("budget exhausted (TOKENS)");
        assertThat(out()).contains("Spend: steps=1 toolCalls=0 tokens=110");
    }

    @Test
    void aStepLimitIsReported() {
        var client = RecordedLlmClient.of(asks(1, 1, call("c1", "list_notes", "{}")), asks(1, 1, call("c2", "list_notes", "{}")));

        int code = run(client, Approver.denyAll(), Budget.defaults().withMaxSteps(2), "ask", "q");

        assertThat(code).isEqualTo(1);
        assertThat(err()).contains("after 2 steps");
    }

    @Test
    void aTruncatedAnswerIsReported() {
        var client = RecordedLlmClient.of(new ChatResult("half", FinishReason.LENGTH, new TokenUsage(1, 1, 2)));

        assertThat(run(client, Approver.denyAll(), Budget.defaults(), "ask", "q")).isEqualTo(1);
        assertThat(err()).contains("cut off");
    }

    @Test
    void aModelFailureIsReportedWithoutAStackTrace() {
        var failing = new io.github.hvmello.aienglab.shared.LlmClient() {
            @Override
            public ChatResult chat(io.github.hvmello.aienglab.shared.ChatRequest request) {
                throw new io.github.hvmello.aienglab.shared.LlmCallException("connection refused", null);
            }

            @Override
            public void stream(io.github.hvmello.aienglab.shared.ChatRequest request,
                    io.github.hvmello.aienglab.shared.StreamListener listener) {
                throw new UnsupportedOperationException();
            }
        };

        int code = Main.run(new String[] {"ask", "q"}, () -> failing, Approver.denyAll(), Budget.defaults(), out, err);

        assertThat(code).isEqualTo(1);
        assertThat(err()).contains("Model call failed: connection refused").doesNotContain("\tat ");
    }

    @Test
    void recordWritesAFileThatReplays() throws Exception {
        Path file = dir.resolve("run.json");

        int code = run(readThenWriteThenAnswer(), Approver.denyAll(), Budget.defaults(),
                "ask", "q", "--record", file.toString());

        assertThat(code).isEqualTo(0);
        assertThat(out()).contains("Recorded 3 turns to");
        var replay = RecordedLlmClient.fromFile(file);
        assertThat(replay.chat(io.github.hvmello.aienglab.shared.ChatRequest.of(
                        io.github.hvmello.aienglab.shared.Message.user("q"))).toolCalls())
                .extracting(c -> c.name())
                .containsExactly("read_note");
        assertThat(Files.readString(file)).contains("\"source\" : \"recorded\"").contains("\"question\" : \"q\"");
    }

    @Test
    void demoNotesAreSeeded() {
        var store = new NoteStore();

        Main.seed(store);

        assertThat(store.names()).containsExactly("shopping", "todo", "welcome");
        assertThat(List.of(store.read("shopping").orElseThrow())).allMatch(s -> s.contains("milk"));
    }
}
