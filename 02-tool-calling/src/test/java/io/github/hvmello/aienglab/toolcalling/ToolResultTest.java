package io.github.hvmello.aienglab.toolcalling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The envelope the model sees is a contract: these are golden strings. */
class ToolResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void successEnvelope() throws Exception {
        var result = ToolResult.ok(JSON.readTree("{\"temp\":21.5}"));

        assertThat(result.toJson()).isEqualTo("{\"ok\":true,\"data\":{\"temp\":21.5}}");
    }

    @Test
    void errorEnvelope() {
        var result = ToolResult.error(ToolResult.ErrorType.TOOL_UNAVAILABLE, "Switched off.", false);

        assertThat(result.toJson()).isEqualTo(
                "{\"ok\":false,\"error\":{\"type\":\"tool_unavailable\",\"message\":\"Switched off.\","
                        + "\"retryable\":false}}");
    }

    @Test
    void invalidArgumentsEnvelopeListsFields() {
        var result = ToolResult.invalidArguments(List.of(
                new FieldProblem("latitude", "must be between -90 and 90"), new FieldProblem("days", "is required")));

        assertThat(result.toJson()).isEqualTo(
                "{\"ok\":false,\"error\":{\"type\":\"invalid_arguments\",\"message\":\"The arguments do not fit the "
                        + "tool. Fix the listed fields and call it again.\",\"retryable\":true,\"fields\":["
                        + "{\"path\":\"latitude\",\"problem\":\"must be between -90 and 90\"},"
                        + "{\"path\":\"days\",\"problem\":\"is required\"}]}}");
    }

    @Test
    void errorTypesKeepTheirWireNames() {
        assertThat(ToolResult.ErrorType.values())
                .extracting(ToolResult.ErrorType::wire)
                .containsExactly("unknown_tool", "invalid_arguments", "tool_error", "tool_unavailable");
    }

    @Test
    void statusSummarisesAnEnvelope() {
        assertThat(ToolResult.statusOf("{\"ok\":true,\"data\":{}}")).isEqualTo("ok");
        assertThat(ToolResult.statusOf(
                        ToolResult.error(ToolResult.ErrorType.TOOL_ERROR, "x", true).toJson()))
                .isEqualTo("error tool_error");
        assertThat(ToolResult.statusOf("not json")).isEqualTo("error unreadable");
    }
}
