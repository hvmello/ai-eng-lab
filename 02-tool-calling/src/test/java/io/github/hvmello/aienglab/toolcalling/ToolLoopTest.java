package io.github.hvmello.aienglab.toolcalling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.Message;
import io.github.hvmello.aienglab.shared.StreamListener;
import io.github.hvmello.aienglab.shared.ToolCall;
import io.github.hvmello.aienglab.shared.ToolSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The loop against a scripted stub model and fake tools: no network, no real model. */
class ToolLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LoopLimits LIMITS = new LoopLimits(6, 4, 2, Duration.ofSeconds(5));

    /** Returns queued results in order (the last one repeats) and records each request. */
    private static final class ScriptedClient implements LlmClient {
        final List<ChatRequest> requests = new ArrayList<>();
        private final List<Supplier<ChatResult>> script;

        @SafeVarargs
        ScriptedClient(Supplier<ChatResult>... script) {
            this.script = List.of(script);
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            return script.get(Math.min(requests.size(), script.size()) - 1).get();
        }

        @Override
        public void stream(ChatRequest request, StreamListener listener) {
            throw new UnsupportedOperationException();
        }
    }

    private static Supplier<ChatResult> asks(ToolCall... calls) {
        return () -> new ChatResult("", FinishReason.TOOL_EXECUTION, null, List.of(calls));
    }

    private static Supplier<ChatResult> says(String text) {
        return () -> new ChatResult(text, FinishReason.STOP, null);
    }

    private static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json);
    }

    record EchoArgs(String text, Integer times) {
        EchoArgs {
            new Arguments.Checks()
                    .require(text != null && !text.isBlank(), "text", "is required")
                    .require(times == null || (times >= 1 && times <= 3), "times", "must be between 1 and 3")
                    .verify();
        }
    }

    /** A fake tool that counts executions and does whatever the test says. */
    private static final class FakeTool implements Tool<EchoArgs> {
        final String name;
        final AtomicInteger executions = new AtomicInteger();
        final List<String> order = new ArrayList<>();
        ThrowingFunction behaviour = args -> JSON.createObjectNode().put("echo", args.text());

        FakeTool(String name) {
            this.name = name;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(
                    name,
                    "Echoes text",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                            + "\"times\":{\"type\":\"integer\"}},\"required\":[\"text\"]}");
        }

        @Override
        public EchoArgs parse(JsonNode arguments) {
            return Arguments.parse(arguments, EchoArgs.class);
        }

        @Override
        public JsonNode execute(EchoArgs args) throws ToolFailure {
            executions.incrementAndGet();
            order.add(args.text());
            return behaviour.apply(args);
        }
    }

    @FunctionalInterface
    interface ThrowingFunction {
        JsonNode apply(EchoArgs args) throws ToolFailure;
    }

    private static JsonNode toolEnvelope(LoopOutcome outcome, int toolMessageIndex) throws Exception {
        var toolMessages = outcome.transcript().stream()
                .filter(m -> m.role() == Message.Role.TOOL)
                .toList();
        return JSON.readTree(toolMessages.get(toolMessageIndex).content());
    }

    @Test
    void twoStepChainEndsInAnsweredWithResultsPairedToTheirCalls() throws Exception {
        var geocode = new FakeTool("geocode");
        var forecast = new FakeTool("forecast");
        var client = new ScriptedClient(
                asks(call("a1", "geocode", "{\"text\":\"Porto Alegre\"}")),
                asks(call("b1", "forecast", "{\"text\":\"-30.03\"}"), call("b2", "geocode", "{\"text\":\"again\"}")),
                says("It will rain."));

        var outcome = new ToolLoop(client, List.of(geocode, forecast), LIMITS).run("Will it rain?");

        assertThat(outcome).isInstanceOf(LoopOutcome.Answered.class);
        assertThat(((LoopOutcome.Answered) outcome).text()).isEqualTo("It will rain.");
        assertThat(client.requests).hasSize(3);
        assertThat(client.requests.get(0).tools()).extracting(ToolSpec::name).containsExactly("geocode", "forecast");
        var toolMessages = outcome.transcript().stream()
                .filter(m -> m.role() == Message.Role.TOOL)
                .toList();
        assertThat(toolMessages).extracting(Message::toolCallId).containsExactly("a1", "b1", "b2");
        assertThat(toolMessages).extracting(Message::toolName).containsExactly("geocode", "forecast", "geocode");
        assertThat(toolEnvelope(outcome, 0).path("ok").asBoolean()).isTrue();
        assertThat(toolEnvelope(outcome, 0).path("data").path("echo").asText()).isEqualTo("Porto Alegre");
        // The second request carried the assistant tool call and its result, in order.
        var second = client.requests.get(1).messages();
        assertThat(second).extracting(Message::role)
                .containsExactly(Message.Role.SYSTEM, Message.Role.USER, Message.Role.ASSISTANT, Message.Role.TOOL);
        // The transcript ends with the final assistant answer.
        var last = outcome.transcript().get(outcome.transcript().size() - 1);
        assertThat(last.role()).isEqualTo(Message.Role.ASSISTANT);
    }

    @Test
    void anUnknownToolGoesBackToTheModelAndTheCorrectedCallSucceeds() throws Exception {
        var tool = new FakeTool("echo");
        var client = new ScriptedClient(
                asks(call("c1", "ech0", "{\"text\":\"hi\"}")),
                asks(call("c2", "echo", "{\"text\":\"hi\"}")),
                says("done"));

        var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

        assertThat(outcome).isInstanceOf(LoopOutcome.Answered.class);
        var first = toolEnvelope(outcome, 0);
        assertThat(first.path("ok").asBoolean()).isFalse();
        assertThat(first.path("error").path("type").asText()).isEqualTo("unknown_tool");
        assertThat(first.path("error").path("message").asText()).contains("echo");
        assertThat(toolEnvelope(outcome, 1).path("ok").asBoolean()).isTrue();
        assertThat(tool.executions).hasValue(1);
    }

    @Test
    void invalidArgumentsReturnFieldPathsAndTheToolNeverRuns() throws Exception {
        var cases = List.of(
                new String[] {"{}", "text"},
                new String[] {"{\"text\":5}", "text"},
                new String[] {"{\"text\":\"x\",\"times\":9}", "times"},
                new String[] {"{\"text\":\"x\",\"times\":\"two\"}", "times"},
                new String[] {"{\"text\":\"x\",\"times\":1.5}", "times"},
                new String[] {"{\"text\":\"x\",\"extra\":true}", "extra"},
                new String[] {"{\"text\":\"x\"", ""},
                new String[] {"[1,2]", ""});
        for (String[] c : cases) {
            var tool = new FakeTool("echo");
            var client = new ScriptedClient(asks(call("i1", "echo", c[0])), says("ok"));

            var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

            var error = toolEnvelope(outcome, 0).path("error");
            assertThat(error.path("type").asText()).as(c[0]).isEqualTo("invalid_arguments");
            assertThat(error.path("retryable").asBoolean()).isTrue();
            assertThat(error.path("fields").get(0).path("path").asText()).as(c[0]).isEqualTo(c[1]);
            assertThat(error.path("fields").get(0).path("problem").asText()).isNotBlank();
            assertThat(tool.executions).as(c[0]).hasValue(0);
        }
    }

    @Test
    void severalProblemsAreReportedTogether() throws Exception {
        var tool = new FakeTool("echo");
        var client = new ScriptedClient(asks(call("i1", "echo", "{\"times\":7}")), says("ok"));

        var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

        var fields = toolEnvelope(outcome, 0).path("error").path("fields");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).path("path").asText()).isEqualTo("text");
        assertThat(fields.get(1).path("path").asText()).isEqualTo("times");
    }

    @Test
    void aToolErrorReachesTheModelWithoutExceptionClassStackUrlOrHost() throws Exception {
        var tool = new FakeTool("echo");
        tool.behaviour = args -> {
            throw new IllegalStateException("java.net.ConnectException at https://secret.internal.example:8443/x "
                    + "Authorization: Bearer abc at com.acme.Db.query(Db.java:42)");
        };
        var client = new ScriptedClient(asks(call("e1", "echo", "{\"text\":\"hi\"}")), says("sorry"));

        var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

        String sent = outcome.transcript().stream()
                .filter(m -> m.role() == Message.Role.TOOL)
                .map(Message::content)
                .findFirst()
                .orElseThrow();
        var error = JSON.readTree(sent).path("error");
        assertThat(error.path("type").asText()).isEqualTo("tool_error");
        assertThat(error.path("retryable").asBoolean()).isFalse();
        assertThat(sent)
                .doesNotContain("java.")
                .doesNotContain("Exception")
                .doesNotContain("secret")
                .doesNotContain("https://")
                .doesNotContain("Bearer")
                .doesNotContain("Db.java")
                .doesNotContain("at com.");
    }

    @Test
    void aToolFailureKeepsItsSafeMessageAndRetryableFlag() throws Exception {
        var tool = new FakeTool("echo");
        tool.behaviour = args -> {
            throw new ToolFailure("The service is busy. Retrying may work.", true);
        };
        var client = new ScriptedClient(asks(call("e1", "echo", "{\"text\":\"hi\"}")), says("ok"));

        var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

        var error = toolEnvelope(outcome, 0).path("error");
        assertThat(error.path("message").asText()).isEqualTo("The service is busy. Retrying may work.");
        assertThat(error.path("retryable").asBoolean()).isTrue();
    }

    @Test
    void theCircuitBreakerStopsExecutingAToolThatKeepsFailing() throws Exception {
        var tool = new FakeTool("echo");
        tool.behaviour = args -> {
            throw new ToolFailure("Busy, try again.", true);
        };
        var client = new ScriptedClient(
                asks(call("f1", "echo", "{\"text\":\"a\"}")),
                asks(call("f2", "echo", "{\"text\":\"b\"}")),
                asks(call("f3", "echo", "{\"text\":\"c\"}")),
                says("giving up"));

        var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

        assertThat(tool.executions).hasValue(LIMITS.maxFailuresPerTool());
        assertThat(toolEnvelope(outcome, 1).path("error").path("type").asText()).isEqualTo("tool_error");
        var third = toolEnvelope(outcome, 2).path("error");
        assertThat(third.path("type").asText()).isEqualTo("tool_unavailable");
        assertThat(third.path("retryable").asBoolean()).isFalse();
        assertThat(outcome).isInstanceOf(LoopOutcome.Answered.class);
    }

    @Test
    void repeatedInvalidArgumentsAlsoTripTheBreaker() throws Exception {
        var tool = new FakeTool("echo");
        var client = new ScriptedClient(
                asks(call("v1", "echo", "{}")),
                asks(call("v2", "echo", "{}")),
                asks(call("v3", "echo", "{\"text\":\"fine\"}")),
                says("ok"));

        var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

        assertThat(toolEnvelope(outcome, 2).path("error").path("type").asText()).isEqualTo("tool_unavailable");
        assertThat(tool.executions).hasValue(0);
    }

    @Test
    void anIdenticalCallThatFailedForGoodIsAnsweredFromTheRecordWithoutRunning() throws Exception {
        var tool = new FakeTool("echo");
        tool.behaviour = args -> {
            throw new ToolFailure("The city does not exist. Repeating will not help.", false);
        };
        var limits = new LoopLimits(6, 4, 5, Duration.ofSeconds(5));
        var client = new ScriptedClient(
                asks(call("r1", "echo", "{\"text\":\"Atlantis\",\"times\":1}")),
                // Same arguments, other key order and spacing.
                asks(call("r2", "echo", "{ \"times\": 1, \"text\": \"Atlantis\" }")),
                says("no such city"));

        var outcome = new ToolLoop(client, List.of(tool), limits).run("go");

        assertThat(tool.executions).hasValue(1);
        assertThat(toolEnvelope(outcome, 1)).isEqualTo(toolEnvelope(outcome, 0));
    }

    @Test
    void aDifferentCallAfterANonRetryableFailureStillRuns() throws Exception {
        var tool = new FakeTool("echo");
        tool.behaviour = args -> {
            if (args.text().equals("bad")) {
                throw new ToolFailure("Rejected.", false);
            }
            return JSON.createObjectNode().put("echo", args.text());
        };
        var client = new ScriptedClient(
                asks(call("d1", "echo", "{\"text\":\"bad\"}")),
                asks(call("d2", "echo", "{\"text\":\"good\"}")),
                says("ok"));

        var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

        assertThat(tool.executions).hasValue(2);
        assertThat(toolEnvelope(outcome, 1).path("ok").asBoolean()).isTrue();
    }

    @Test
    void theStepLimitBoundsAModelThatNeverStopsAskingForTools() {
        var tool = new FakeTool("echo");
        var client = new ScriptedClient(asks(call("s", "echo", "{\"text\":\"hi\"}")));
        var limits = new LoopLimits(3, 4, 99, Duration.ofSeconds(5));

        var outcome = new ToolLoop(client, List.of(tool), limits).run("go");

        assertThat(outcome).isInstanceOf(LoopOutcome.StepLimitReached.class);
        assertThat(client.requests).hasSize(3);
        assertThat(outcome.transcript().stream().filter(m -> m.role() == Message.Role.TOOL)).hasSize(3);
    }

    @Test
    void callsBeyondThePerStepLimitGetAStructuredErrorAndAreNotExecuted() throws Exception {
        var tool = new FakeTool("echo");
        var calls = new ToolCall[5];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = call("m" + i, "echo", "{\"text\":\"t" + i + "\"}");
        }
        var client = new ScriptedClient(asks(calls), says("ok"));
        var limits = new LoopLimits(6, 2, 2, Duration.ofSeconds(5));

        var outcome = new ToolLoop(client, List.of(tool), limits).run("go");

        assertThat(tool.executions).hasValue(2);
        assertThat(tool.order).containsExactly("t0", "t1");
        assertThat(toolEnvelope(outcome, 1).path("ok").asBoolean()).isTrue();
        for (int i = 2; i < 5; i++) {
            var error = toolEnvelope(outcome, i).path("error");
            assertThat(error.path("type").asText()).isEqualTo("tool_error");
            assertThat(error.path("retryable").asBoolean()).isTrue();
            assertThat(error.path("message").asText()).contains("next step");
        }
        // Every requested call still got a result with its own id.
        assertThat(outcome.transcript().stream()
                        .filter(m -> m.role() == Message.Role.TOOL)
                        .map(Message::toolCallId))
                .containsExactly("m0", "m1", "m2", "m3", "m4");
    }

    @Test
    void aSlowToolTimesOutWithARetryableError() throws Exception {
        var tool = new FakeTool("echo");
        tool.behaviour = args -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return JSON.createObjectNode();
        };
        var limits = new LoopLimits(6, 4, 2, Duration.ofMillis(100));
        var client = new ScriptedClient(asks(call("t1", "echo", "{\"text\":\"hi\"}")), says("ok"));

        var outcome = new ToolLoop(client, List.of(tool), limits).run("go");

        var error = toolEnvelope(outcome, 0).path("error");
        assertThat(error.path("type").asText()).isEqualTo("tool_error");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("message").asText()).contains("in time");
    }

    @Test
    void aTruncatedAnswerIsNotAnAnswer() {
        var tool = new FakeTool("echo");
        var client = new ScriptedClient(() -> new ChatResult("The answer is", FinishReason.LENGTH, null));

        var outcome = new ToolLoop(client, List.of(tool), LIMITS).run("go");

        assertThat(outcome).isInstanceOf(LoopOutcome.Truncated.class);
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void aModelFailurePropagatesAfterOneCallAndIsNeverRetried() {
        var tool = new FakeTool("echo");
        var client = new ScriptedClient(() -> {
            throw new LlmCallException("boom", null);
        });

        assertThatThrownBy(() -> new ToolLoop(client, List.of(tool), LIMITS).run("go"))
                .isInstanceOf(LlmCallException.class);
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void duplicateToolNamesAreRejected() {
        var client = new ScriptedClient(says("x"));

        assertThatThrownBy(() -> new ToolLoop(client, List.of(new FakeTool("a"), new FakeTool("a")), LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsAreValidated() {
        assertThatThrownBy(() -> new LoopLimits(0, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoopLimits(1, 1, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LoopLimits.defaults()).isEqualTo(new LoopLimits(6, 4, 2, Duration.ofSeconds(10)));
    }
}
