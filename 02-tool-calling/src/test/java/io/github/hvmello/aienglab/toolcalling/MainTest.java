package io.github.hvmello.aienglab.toolcalling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.StreamListener;
import io.github.hvmello.aienglab.shared.ToolCall;
import io.github.hvmello.aienglab.shared.ToolSpec;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MainTest {

    private static final class StubClient implements LlmClient {
        final List<ChatResult> script;
        int calls;

        StubClient(ChatResult... script) {
            this.script = List.of(script);
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            return script.get(Math.min(calls++, script.size() - 1));
        }

        @Override
        public void stream(ChatRequest request, StreamListener listener) {
            throw new UnsupportedOperationException();
        }
    }

    record PingArgs(String text) {}

    /** A tool whose failure message carries an upstream body, to prove the trace never prints it. */
    private static final class RawBodyTool implements Tool<PingArgs> {
        @Override
        public ToolSpec spec() {
            return new ToolSpec(
                    "ping", "Pings", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
        }

        @Override
        public PingArgs parse(JsonNode arguments) {
            return Arguments.parse(arguments, PingArgs.class);
        }

        @Override
        public JsonNode execute(PingArgs args) throws ToolFailure {
            throw new ToolFailure("The service answered <html>RAW UPSTREAM BODY</html>", false);
        }
    }

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final List<Main.Lib> libsUsed = new ArrayList<>();

    private int run(StubClient client, String... args) {
        Function<Main.Lib, LlmClient> factory = lib -> {
            libsUsed.add(lib);
            return client;
        };
        return Main.run(args, factory, List.of(new RawBodyTool()), LoopLimits.defaults(), out, err);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private static ChatResult answer(String text) {
        return new ChatResult(text, FinishReason.STOP, null);
    }

    @Test
    void usageErrorsExitWithTwo() {
        var client = new StubClient(answer("x"));

        assertThat(run(client)).isEqualTo(2);
        assertThat(run(client, "chat", "hi")).isEqualTo(2);
        assertThat(run(client, "ask")).isEqualTo(2);
        assertThat(run(client, "ask", "--lib")).isEqualTo(2);
        assertThat(run(client, "ask", "hi", "--lib", "nope")).isEqualTo(2);
        assertThat(err()).contains("Usage:");
        assertThat(client.calls).isZero();
        assertThat(libsUsed).isEmpty();
    }

    @Test
    void theDefaultLibraryIsLangChain4j() {
        var code = run(new StubClient(answer("Sunny")), "ask", "Will", "it", "rain?");

        assertThat(code).isZero();
        assertThat(libsUsed).containsExactly(Main.Lib.LANGCHAIN4J);
        assertThat(out()).contains("Sunny");
    }

    @Test
    void libIsParsedWhereverItAppears() {
        run(new StubClient(answer("a")), "ask", "--lib", "springai", "hello");
        run(new StubClient(answer("b")), "ask", "hello", "--lib", "langchain4j");

        assertThat(libsUsed).containsExactly(Main.Lib.SPRINGAI, Main.Lib.LANGCHAIN4J);
    }

    @Test
    void theTraceShowsStatusButNeverTheRawUpstreamBodyOrArguments() {
        var call = new ToolCall("c1", "ping", "{\"text\":\"secret-argument\"}");
        var client = new StubClient(
                new ChatResult("", FinishReason.TOOL_EXECUTION, null, List.of(call)), answer("The service is down."));

        var code = run(client, "ask", "ping it");

        assertThat(code).isZero();
        assertThat(out()).contains("[step 1] ping -> error tool_error").contains("The service is down.");
        assertThat(out()).doesNotContain("RAW UPSTREAM BODY").doesNotContain("<html>").doesNotContain("secret-argument");
        assertThat(err()).doesNotContain("RAW UPSTREAM BODY");
    }

    @Test
    void aStepLimitIsReportedAsAFailure() {
        var call = new ToolCall("c1", "ping", "{\"text\":\"x\"}");
        var client = new StubClient(new ChatResult("", FinishReason.TOOL_EXECUTION, null, List.of(call)));

        var code = Main.run(
                new String[] {"ask", "loop"},
                lib -> client,
                List.of(new RawBodyTool()),
                new LoopLimits(2, 4, 9, Duration.ofSeconds(2)),
                out,
                err);

        assertThat(code).isEqualTo(1);
        assertThat(err()).contains("after 2 steps");
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void aTruncatedAnswerIsReportedAsAFailure() {
        var client = new StubClient(new ChatResult("cut", FinishReason.LENGTH, null));

        assertThat(run(client, "ask", "x")).isEqualTo(1);
        assertThat(err()).contains("cut off");
        assertThat(out()).doesNotContain("cut");
    }

    @Test
    void aModelFailureExitsWithOne() {
        var failing = new LlmClient() {
            @Override
            public ChatResult chat(ChatRequest request) {
                throw new LlmCallException("connection refused", null);
            }

            @Override
            public void stream(ChatRequest request, StreamListener listener) {
                throw new UnsupportedOperationException();
            }
        };

        var code = Main.run(new String[] {"ask", "x"}, lib -> failing, List.of(), LoopLimits.defaults(), out, err);

        assertThat(code).isEqualTo(1);
        assertThat(err()).contains("Model call failed");
    }
}
