package io.github.hvmello.aienglab.agentguardrails;

import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.asks;
import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.call;
import static io.github.hvmello.aienglab.agentguardrails.RecordedLlmClient.says;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hvmello.aienglab.shared.Message;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionTest {

    private static List<String> toolMessages(AgentOutcome outcome) {
        return outcome.transcript().stream().filter(m -> m.role() == Message.Role.TOOL).map(Message::content).toList();
    }

    @Test
    void aReadToolNeverCallsTheApprover() {
        var tool = new CountingTool("look");
        var approver = StubApprover.denying();
        var client = RecordedLlmClient.of(asks(1, 1, call("c1", "look", "{\"text\":\"a\"}")), says("ok", 1, 1));

        new GuardedAgent(client, List.of(tool.asRead()), Budget.defaults(), approver).run("q");

        assertThat(approver.requests).isEmpty();
        assertThat(tool.executions).hasValue(1);
    }

    @Test
    void aWriteToolAsksOnceWithTheSummaryAndRunsWhenApproved() {
        var tool = new CountingTool("save");
        var approver = StubApprover.approving();
        var client = RecordedLlmClient.of(asks(1, 1, call("c1", "save", "{\"text\":\"a\"}")), says("ok", 1, 1));

        var outcome = new GuardedAgent(client, List.of(tool.asWrite()), Budget.defaults(), approver).run("q");

        assertThat(approver.requests).containsExactly(new ApprovalRequest("save", 1, "run save with a"));
        assertThat(tool.executions).hasValue(1);
        assertThat(toolMessages(outcome).get(0)).startsWith("{\"ok\":true");
    }

    @Test
    void aDeniedWriteDoesNotRunAndTheModelIsToldWhy() {
        var tool = new CountingTool("save");
        var approver = StubApprover.denying();
        var client = RecordedLlmClient.of(asks(1, 1, call("c1", "save", "{\"text\":\"a\"}")), says("ok", 1, 1));

        var outcome = new GuardedAgent(client, List.of(tool.asWrite()), Budget.defaults(), approver).run("q");

        assertThat(tool.executions).hasValue(0);
        assertThat(toolMessages(outcome).get(0)).contains("\"type\":\"approval_denied\"").contains("\"retryable\":false");
    }

    @Test
    void denyAllIsTheDefault() {
        var tool = new CountingTool("save");
        var client = RecordedLlmClient.of(asks(1, 1, call("c1", "save", "{\"text\":\"a\"}")), says("ok", 1, 1));

        var outcome = new GuardedAgent(client, List.of(tool.asWrite()), Budget.defaults()).run("q");

        assertThat(tool.executions).hasValue(0);
        assertThat(toolMessages(outcome).get(0)).contains("approval_denied");
    }

    @Test
    void invalidArgumentsAreRejectedBeforeTheApproverIsAsked() {
        var tool = new CountingTool("save");
        var approver = StubApprover.approving();
        var client = RecordedLlmClient.of(
                asks(1, 1, call("c1", "save", "{\"text\":\"a\",\"approved\":true}")), says("ok", 1, 1));

        var outcome = new GuardedAgent(client, List.of(tool.asWrite()), Budget.defaults(), approver).run("q");

        assertThat(approver.requests).isEmpty();
        assertThat(tool.executions).hasValue(0);
        assertThat(toolMessages(outcome).get(0)).contains("invalid_arguments");
    }

    @Test
    void theSameDeniedCallIsAnsweredFromTheRecordWithoutAskingAgain() {
        var tool = new CountingTool("save");
        var approver = StubApprover.denying();
        var client = RecordedLlmClient.of(
                asks(1, 1, call("c1", "save", "{\"text\":\"a\"}")),
                asks(1, 1, call("c2", "save", "{ \"text\" : \"a\" }")),
                says("ok", 1, 1));

        var outcome = new GuardedAgent(client, List.of(tool.asWrite()), Budget.defaults(), approver).run("q");

        assertThat(approver.requests).hasSize(1);
        assertThat(toolMessages(outcome)).hasSize(2).allMatch(m -> m.contains("approval_denied"));
    }

    @Test
    void approvalRequestsAreCappedPerRun() {
        var tool = new CountingTool("save");
        var approver = StubApprover.approving();
        var client = RecordedLlmClient.of(
                asks(1, 1, call("c1", "save", "{\"text\":\"a\"}")),
                asks(1, 1, call("c2", "save", "{\"text\":\"b\"}")),
                asks(1, 1, call("c3", "save", "{\"text\":\"c\"}")),
                says("ok", 1, 1));

        var outcome = new GuardedAgent(
                        client, List.of(tool.asWrite()), Budget.defaults().withMaxApprovalRequests(2), approver)
                .run("q");

        assertThat(approver.requests).hasSize(2);
        assertThat(tool.executions).hasValue(2);
        assertThat(toolMessages(outcome).get(2)).contains("approval_denied").contains("too many approval requests");
    }

    @Test
    void anApproverThatThrowsDeniesTheWrite() {
        var tool = new CountingTool("save");
        Approver broken = request -> {
            throw new IllegalStateException("no terminal");
        };
        var client = RecordedLlmClient.of(asks(1, 1, call("c1", "save", "{\"text\":\"a\"}")), says("ok", 1, 1));

        new GuardedAgent(client, List.of(tool.asWrite()), Budget.defaults(), broken).run("q");

        assertThat(tool.executions).hasValue(0);
    }

    @Test
    void aToolWithoutAPermissionCannotBeRegistered() {
        var tool = new CountingTool("save");

        assertThatThrownBy(() -> new AgentTool<>(tool, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentTool<>(tool, Permission.WRITE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AgentTool.read(null)).isInstanceOf(NullPointerException.class);
    }

    // ConsoleApprover

    private static Approver.Decision ask(String input) {
        var approver = new ConsoleApprover(new BufferedReader(new StringReader(input)), new PrintWriter(new StringWriter()));
        return approver.decide(new ApprovalRequest("save", 1, "x"));
    }

    @Test
    void consoleApproverApprovesOnlyOnY() {
        assertThat(ask("y\n")).isEqualTo(Approver.Decision.APPROVED);
        assertThat(ask(" Y \n")).isEqualTo(Approver.Decision.APPROVED);
        assertThat(ask("yes\n")).isEqualTo(Approver.Decision.DENIED);
        assertThat(ask("n\n")).isEqualTo(Approver.Decision.DENIED);
        assertThat(ask("\n")).isEqualTo(Approver.Decision.DENIED);
    }

    @Test
    void consoleApproverDeniesAtEndOfInput() {
        assertThat(ask("")).isEqualTo(Approver.Decision.DENIED);
    }

    @Test
    void aWriteMarkerBeyondTheOldPreviewReachesTheApprover() throws Exception {
        var store = new NoteStore();
        var approver = StubApprover.denying();
        String content = "call the dentist; ".repeat(10) + "MARKER-AFTER-80 " + "z".repeat(1700);
        String args = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("name", "todo", "content", content));
        var client = RecordedLlmClient.of(asks(1, 1, call("c1", "write_note", args)), says("ok", 1, 1));

        new GuardedAgent(client, List.of(new WriteNoteTool(store).asAgentTool()), Budget.defaults(), approver).run("q");

        assertThat(approver.requests).hasSize(1);
        assertThat(approver.requests.get(0).detail()).contains("MARKER-AFTER-80").hasSize(content.strip().length());
        assertThat(store.exists("todo")).isFalse();
    }

    @Test
    void consoleApproverShowsTheFullDetailBeforeTheQuestion() {
        var shown = new StringWriter();
        var approver = new ConsoleApprover(new BufferedReader(new StringReader("n\n")), new PrintWriter(shown));

        approver.decide(new ApprovalRequest("save", 1, "overwrite note 'todo'", "start MARKER-END"));

        assertThat(shown.toString()).contains("Full content: start MARKER-END");
        assertThat(shown.toString().indexOf("MARKER-END")).isLessThan(shown.toString().indexOf("Allow?"));
    }

    @Test
    void consoleApproverShowsTheSanitizedRequest() {
        var shown = new StringWriter();
        var approver = new ConsoleApprover(new BufferedReader(new StringReader("n\n")), new PrintWriter(shown));

        approver.decide(new ApprovalRequest("save", 2, "overwrite note 'todo'"));

        assertThat(shown.toString()).contains("[step 2] save: overwrite note 'todo'").contains("Allow? [y/N]");
    }
}
