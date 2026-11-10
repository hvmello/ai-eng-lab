package io.github.hvmello.aienglab.agentguardrails;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hvmello.aienglab.shared.Message;
import io.github.hvmello.aienglab.toolcalling.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Replays recorded model turns against the real guard code. The model is a script, so this proves what the code does
 * when a model misbehaves, not how likely a real model is to misbehave. Adding a case is adding a JSON file to
 * {@code src/test/resources/injection/}.
 *
 * <p>Fixture: {@code source}, {@code description}, {@code notes}, {@code question}, {@code approver}
 * ({@code deny} by default), {@code budget} overrides, {@code turns} and {@code expect}.
 */
class InjectionReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIR = Path.of("src/test/resources/injection");

    static Stream<Arguments> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted()
                    .map(p -> Arguments.of(p.getFileName().toString(), p)).toList().stream();
        }
    }

    @Test
    void theMinimumSetIsPresent() throws IOException {
        List<String> names = fixtures().map(a -> (String) a.get()[0]).toList();

        assertThat(names).hasSizeGreaterThanOrEqualTo(8);
        assertThat(names).anyMatch(n -> n.startsWith("01-")).anyMatch(n -> n.startsWith("08-"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void replay(String name, Path file) throws IOException {
        JsonNode fixture = JSON.readTree(Files.readString(file));
        assertThat(fixture.get("source").asText()).isIn("synthetic", "recorded");
        JsonNode expect = fixture.get("expect");

        NoteStore store = new NoteStore();
        Map<String, String> seeded = new TreeMap<>();
        fixture.path("notes").fields().forEachRemaining(e -> {
            store.write(e.getKey(), e.getValue().asText());
            seeded.put(e.getKey(), e.getValue().asText());
        });
        RecordedLlmClient client = RecordedLlmClient.fromJson(fixture.get("turns"));
        StubApprover approver = fixture.path("approver").asText("deny").equals("approve")
                ? StubApprover.approving()
                : StubApprover.denying();
        List<AgentTool<?>> tools = List.of(
                new ListNotesTool(store).asAgentTool(),
                new ReadNoteTool(store).asAgentTool(),
                new WriteNoteTool(store).asAgentTool());

        AgentOutcome outcome = new GuardedAgent(client, tools, budget(fixture.path("budget")), approver)
                .run(fixture.get("question").asText());

        // Every recorded turn was used, so a stale recording fails loudly.
        client.assertConsumed();

        // Invariants that hold for every case.
        List<Message> transcript = outcome.transcript();
        assertThat(transcript.stream().filter(m -> m.role() == Message.Role.SYSTEM)).hasSize(1);
        List<String> toolMessages = transcript.stream()
                .filter(m -> m.role() == Message.Role.TOOL).map(Message::content).toList();
        for (String message : toolMessages) {
            JsonNode envelope = JSON.readTree(message);
            assertThat(envelope.has("ok")).as("tool message is a real envelope: %s", message).isTrue();
        }
        for (ApprovalRequest request : approver.requests) {
            assertThat((request.summary() + request.detail() + request.tool()).chars()).noneMatch(Character::isISOControl);
        }
        if (!expect.has("notes")) {
            assertThat(store.snapshot()).as("notes are unchanged").isEqualTo(seeded);
        }

        // Expectations of this case.
        assertThat(describe(outcome)).isEqualTo(expect.get("outcome").asText());
        assertThat(approver.requests).hasSize(expect.get("approvalRequests").asInt());
        if (expect.has("toolStatuses")) {
            List<String> expected = new ArrayList<>();
            expect.get("toolStatuses").forEach(s -> expected.add(s.asText()));
            assertThat(toolMessages.stream().map(ToolResult::statusOf).toList()).isEqualTo(expected);
        }
        for (JsonNode snippet : expect.path("toolDataContains")) {
            assertThat(toolMessages).anyMatch(m -> contentOf(m).contains(snippet.asText()));
        }
        for (JsonNode snippet : expect.path("approvalSummariesContain")) {
            assertThat(approver.requests).anyMatch(r -> r.summary().contains(snippet.asText()));
        }
        for (JsonNode snippet : expect.path("approvalDetailsContain")) {
            assertThat(approver.requests).anyMatch(r -> r.detail().contains(snippet.asText()));
        }
        for (JsonNode snippet : expect.path("approvalSummariesExclude")) {
            assertThat(approver.requests).noneMatch(r -> r.summary().contains(snippet.asText()));
        }
    }

    private static String contentOf(String envelope) {
        try {
            return JSON.readTree(envelope).path("data").path("content").asText("");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Budget budget(JsonNode overrides) {
        Budget budget = Budget.defaults();
        if (overrides.has("maxSteps")) {
            budget = budget.withMaxSteps(overrides.get("maxSteps").asInt());
        }
        if (overrides.has("maxToolCallsTotal")) {
            budget = budget.withMaxToolCallsTotal(overrides.get("maxToolCallsTotal").asInt());
        }
        if (overrides.has("maxToolCallsPerStep")) {
            budget = budget.withMaxToolCallsPerStep(overrides.get("maxToolCallsPerStep").asInt());
        }
        if (overrides.has("maxTotalTokens")) {
            budget = budget.withMaxTotalTokens(overrides.get("maxTotalTokens").asLong());
        }
        if (overrides.has("maxApprovalRequests")) {
            budget = budget.withMaxApprovalRequests(overrides.get("maxApprovalRequests").asInt());
        }
        return budget;
    }

    private static String describe(AgentOutcome outcome) {
        return switch (outcome) {
            case AgentOutcome.Answered a -> "Answered";
            case AgentOutcome.StepLimitReached s -> "StepLimitReached";
            case AgentOutcome.BudgetExhausted e -> "BudgetExhausted:" + e.reason();
            case AgentOutcome.Truncated t -> "Truncated";
        };
    }
}
