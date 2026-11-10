package io.github.hvmello.aienglab.llmbasics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.StreamListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MainTest {

    private static final class Capture {
        final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        String out() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static LlmClient client(String answer, boolean failStream) {
        return new LlmClient() {
            @Override
            public ChatResult chat(ChatRequest request) {
                return new ChatResult(answer, FinishReason.STOP, null);
            }

            @Override
            public void stream(ChatRequest request, StreamListener listener) {
                listener.onToken("Hel");
                listener.onToken("lo");
                if (failStream) {
                    listener.onError(new LlmCallException("dropped", null));
                } else {
                    listener.onComplete(new ChatResult("Hello", FinishReason.STOP, null));
                }
            }
        };
    }

    @Test
    void chatPrintsTheAnswer() {
        var c = new Capture();

        int code = Main.run(new String[] {"chat", "hi", "there"}, client("hello", false), c.out, c.err);

        assertThat(code).isZero();
        assertThat(c.out()).contains("hello").contains("finish=STOP");
    }

    @Test
    void streamPrintsTokensAsTheyArrive() {
        var c = new Capture();

        int code = Main.run(new String[] {"stream", "hi"}, client("x", false), c.out, c.err);

        assertThat(code).isZero();
        assertThat(c.out()).startsWith("Hello");
    }

    @Test
    void streamFailureGivesANonZeroExit() {
        var c = new Capture();

        int code = Main.run(new String[] {"stream", "hi"}, client("x", true), c.out, c.err);

        assertThat(code).isEqualTo(1);
        assertThat(c.err()).contains("Stream failed");
    }

    @Test
    void extractPrintsTheRecord() {
        var c = new Capture();
        String json = "{\"vendor\":\"A\",\"currency\":\"USD\",\"issueDate\":\"2026-03-14\","
                + "\"lineItems\":[{\"description\":\"d\",\"quantity\":1,\"unitPrice\":5}],\"total\":5}";

        int code = Main.run(new String[] {"extract"}, client(json, false), c.out, c.err);

        assertThat(code).isZero();
        assertThat(c.out()).contains("Invoice[vendor=A");
    }

    @Test
    void extractFailureShowsTheTypedErrorAndRawOutput() {
        var c = new Capture();

        int code = Main.run(new String[] {"extract"}, client("not json", false), c.out, c.err);

        assertThat(code).isEqualTo(1);
        assertThat(c.err()).contains("MalformedJson").contains("Raw output: not json");
    }

    @Test
    void extremeExponentIsAnExtractionFailureNotBadUsage() {
        var c = new Capture();
        String json = "{\"vendor\":\"A\",\"currency\":\"USD\",\"issueDate\":\"2026-03-14\","
                + "\"lineItems\":[{\"description\":\"d\",\"quantity\":1,\"unitPrice\":1e-2147483648}],\"total\":5}";

        int code = Main.run(new String[] {"extract"}, client(json, false), c.out, c.err);

        assertThat(code).isEqualTo(1);
        assertThat(c.err()).contains("InvalidValue").contains("Raw output:");
    }

    @Test
    void badUsageGivesExitCodeTwo() {
        var c = new Capture();

        assertThat(Main.run(new String[] {}, client("x", false), c.out, c.err)).isEqualTo(2);
        assertThat(Main.run(new String[] {"chat"}, client("x", false), c.out, c.err)).isEqualTo(2);
        assertThat(Main.run(new String[] {"dance"}, client("x", false), c.out, c.err)).isEqualTo(2);
    }

    /** The module code depends on the abstraction in shared/, never on LangChain4j (ADR 0001). */
    @Test
    void mainSourcesDoNotImportLangChain4j() throws IOException {
        Path root = Path.of("src", "main");
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> offenders = files.filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("dev.langchain4j");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }
}
