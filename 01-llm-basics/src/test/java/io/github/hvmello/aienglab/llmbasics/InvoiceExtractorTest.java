package io.github.hvmello.aienglab.llmbasics;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hvmello.aienglab.llmbasics.ExtractionException.InvalidValue;
import io.github.hvmello.aienglab.llmbasics.ExtractionException.MalformedJson;
import io.github.hvmello.aienglab.llmbasics.ExtractionException.SchemaMismatch;
import io.github.hvmello.aienglab.llmbasics.ExtractionException.TruncatedOutput;
import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.StreamListener;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The extraction pipeline against a stub client. No model, network or key is involved. */
class InvoiceExtractorTest {

    private static final String VALID = """
            {"vendor":"Northwind Traders","currency":"USD","issueDate":"2026-03-14",
             "lineItems":[{"description":"Keyboard","quantity":2,"unitPrice":49.90},
                          {"description":"Dock","quantity":1,"unitPrice":129.00}],"total":228.80}""";

    private static final class StubClient implements LlmClient {
        final List<ChatRequest> requests = new ArrayList<>();
        final ChatResult answer;

        StubClient(String text, FinishReason reason) {
            this.answer = new ChatResult(text, reason, null);
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            return answer;
        }

        @Override
        public void stream(ChatRequest request, StreamListener listener) {
            throw new UnsupportedOperationException();
        }
    }

    private static Invoice extract(String modelOutput) {
        return new InvoiceExtractor(new StubClient(modelOutput, FinishReason.STOP)).extract("some invoice text");
    }

    /** Replaces one fragment of the valid output, failing loudly if the fragment is not there. */
    private static String valid(String from, String to) {
        assertThat(VALID).contains(from);
        return VALID.replace(from, to);
    }

    @Test
    void validOutputReturnsTheRecord() {
        Invoice invoice = extract(VALID);

        assertThat(invoice.vendor()).isEqualTo("Northwind Traders");
        assertThat(invoice.currency()).isEqualTo("USD");
        assertThat(invoice.issueDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(invoice.lineItems()).hasSize(2);
        assertThat(invoice.total()).isEqualByComparingTo("228.80");
    }

    @Test
    void theRequestCarriesTheSchemaAndTheText() {
        var client = new StubClient(VALID, FinishReason.STOP);

        new InvoiceExtractor(client).extract("ACME invoice text");

        ChatRequest request = client.requests.get(0);
        assertThat(request.jsonSchema()).isEqualTo(InvoiceExtractor.schema()).contains("lineItems");
        assertThat(request.messages().get(1).content()).isEqualTo("ACME invoice text");
    }

    @Test
    void truncatedOutputIsReportedEvenWhenItWouldParse() {
        var client = new StubClient(VALID, FinishReason.LENGTH);

        assertThatThrownBy(() -> new InvoiceExtractor(client).extract("x"))
                .isInstanceOf(TruncatedOutput.class)
                .satisfies(e -> assertThat(((ExtractionException) e).rawOutput()).isEqualTo(VALID));
    }

    @Test
    void cutOffJsonIsMalformed() {
        var cut = VALID.substring(0, 60);
        assertFailure(cut, MalformedJson.class);
    }

    @Test
    void nonJsonIsMalformed() {
        assertFailure("Sure! Here is the invoice you asked for.", MalformedJson.class);
    }

    @Test
    void emptyOutputIsMalformed() {
        assertFailure("", MalformedJson.class);
        assertFailure("   \n", MalformedJson.class);
    }

    @Test
    void trailingTextAfterTheJsonIsMalformedOrMismatched() {
        assertThatThrownBy(() -> extract(VALID + " and some chatter")).isInstanceOf(ExtractionException.class);
    }

    @Test
    void wrongTypeIsASchemaMismatch() {
        assertFailure(valid("\"total\":228.80", "\"total\":\"228.80\""), SchemaMismatch.class);
        assertFailure(valid("\"quantity\":2", "\"quantity\":\"2\""), SchemaMismatch.class);
        assertFailure(valid("\"quantity\":2", "\"quantity\":2.5"), SchemaMismatch.class);
    }

    @Test
    void notAnObjectIsASchemaMismatch() {
        assertFailure("[1,2,3]", SchemaMismatch.class);
        assertFailure("\"just a string\"", SchemaMismatch.class);
    }

    @Test
    void jsonNullIsASchemaMismatch() {
        assertFailure("null", SchemaMismatch.class);
    }

    @Test
    void missingFieldIsASchemaMismatch() {
        assertFailure(valid("\"vendor\":\"Northwind Traders\",", ""), SchemaMismatch.class);
        assertFailure(valid(",\"total\":228.80", ""), SchemaMismatch.class);
        assertFailure(valid(",\"unitPrice\":129.00", ""), SchemaMismatch.class);
    }

    @Test
    void explicitNullIsASchemaMismatch() {
        assertFailure(valid("\"vendor\":\"Northwind Traders\"", "\"vendor\":null"), SchemaMismatch.class);
    }

    @Test
    void unknownFieldIsASchemaMismatch() {
        assertFailure(valid("\"currency\":\"USD\",", "\"currency\":\"USD\",\"note\":\"paid\","), SchemaMismatch.class);
    }

    @Test
    void impossibleDateIsASchemaMismatch() {
        assertFailure(valid("2026-03-14", "2026-13-45"), SchemaMismatch.class);
    }

    @Test
    void negativeOrZeroAmountsAreInvalidValues() {
        assertFailure(valid("\"unitPrice\":129.00", "\"unitPrice\":-129.00"), InvalidValue.class);
        assertFailure(valid("\"quantity\":1", "\"quantity\":0"), InvalidValue.class);
        assertFailure(valid("\"total\":228.80", "\"total\":-228.80"), InvalidValue.class);
    }

    @Test
    void amountsThatDifferOnlyPastDoublePrecisionAreInvalidValues() {
        String output = """
                {"vendor":"Northwind Traders","currency":"USD","issueDate":"2026-03-14",
                 "lineItems":[{"description":"Dock","quantity":1,"unitPrice":12345678901234567.89}],
                 "total":12345678901234567.80}""";
        assertFailure(output, InvalidValue.class);
    }

    @Test
    void highPrecisionAmountsAreKeptExactly() {
        String output = """
                {"vendor":"Northwind Traders","currency":"USD","issueDate":"2026-03-14",
                 "lineItems":[{"description":"Dock","quantity":1,"unitPrice":12345678901234567.89}],
                 "total":12345678901234567.89}""";
        assertThat(extract(output).total()).isEqualByComparingTo(new BigDecimal("12345678901234567.89"));
    }

    @Test
    void extremeExponentsFailQuicklyAsInvalidValues() {
        for (String value : new String[] {"1e-500000000", "1e999999999"}) {
            String output = """
                    {"vendor":"Northwind Traders","currency":"USD","issueDate":"2026-03-14",
                     "lineItems":[{"description":"Dock","quantity":1,"unitPrice":10},
                                  {"description":"Cable","quantity":1,"unitPrice":%s}],
                     "total":10}""".formatted(value);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                    () -> assertFailure(output, InvalidValue.class));
        }
        String hugeTotal = """
                {"vendor":"Northwind Traders","currency":"USD","issueDate":"2026-03-14",
                 "lineItems":[{"description":"Dock","quantity":1,"unitPrice":10}],
                 "total":1e-500000000}""";
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> assertFailure(hugeTotal, InvalidValue.class));
    }

    @Test
    void badCurrencyIsAnInvalidValue() {
        assertFailure(valid("\"USD\"", "\"DOLLARS\""), InvalidValue.class);
        assertFailure(valid("\"USD\"", "\"usd\""), InvalidValue.class);
        assertFailure(valid("\"USD\"", "\"ZZZ\""), InvalidValue.class);
    }

    @Test
    void blankVendorAndNoLinesAreInvalidValues() {
        assertFailure(valid("Northwind Traders", " "), InvalidValue.class);
        assertFailure(
                "{\"vendor\":\"A\",\"currency\":\"USD\",\"issueDate\":\"2026-03-14\",\"lineItems\":[],\"total\":5}",
                InvalidValue.class);
    }

    @Test
    void totalsThatDoNotAddUpAreAnInvalidValue() {
        assertFailure(valid("\"total\":228.80", "\"total\":300.00"), InvalidValue.class);
    }

    @Test
    void everyFailureKeepsTheRawOutput() {
        Stream.of(
                        "not json",
                        valid("\"total\":228.80", "\"total\":\"x\""),
                        valid("\"total\":228.80", "\"total\":1.00"))
                .forEach(raw -> assertThatThrownBy(() -> extract(raw))
                        .isInstanceOf(ExtractionException.class)
                        .satisfies(e -> assertThat(((ExtractionException) e).rawOutput()).isEqualTo(raw)));
    }

    /**
     * Known limitation, pinned on purpose. Every price is ten times too high and the total agrees with the lines,
     * so the output is consistent and wrong. Nothing in the document can reveal that, so it is accepted.
     */
    @Test
    void aConsistentButWrongValueIsAccepted() {
        String wrongButConsistent = valid("\"unitPrice\":49.90", "\"unitPrice\":499.00")
                .replace("\"unitPrice\":129.00", "\"unitPrice\":1290.00")
                .replace("\"total\":228.80", "\"total\":2288.00");

        Invoice invoice = extract(wrongButConsistent);

        assertThat(invoice.total()).isEqualByComparingTo(new BigDecimal("2288.00"));
    }

    @Test
    void providerFailuresAreNotExtractionFailures() {
        LlmClient failing = new LlmClient() {
            @Override
            public ChatResult chat(ChatRequest request) {
                throw new LlmCallException("connection refused", null);
            }

            @Override
            public void stream(ChatRequest request, StreamListener listener) {}
        };

        assertThatThrownBy(() -> new InvoiceExtractor(failing).extract("x")).isInstanceOf(LlmCallException.class);
    }

    @Test
    void everySchemaPropertyMatchesARecordComponent() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(InvoiceExtractor.schema());

        assertObjectMatches(schema, Invoice.class);
        assertObjectMatches(schema.path("properties").path("lineItems").path("items"), Invoice.LineItem.class);
    }

    private static void assertObjectMatches(JsonNode objectSchema, Class<? extends Record> type) {
        List<String> components = new ArrayList<>();
        for (var component : type.getRecordComponents()) {
            components.add(component.getName());
        }
        List<String> properties = new ArrayList<>();
        objectSchema.path("properties").fieldNames().forEachRemaining(properties::add);
        List<String> required = new ArrayList<>();
        objectSchema.path("required").forEach(n -> required.add(n.asText()));

        assertThat(properties).containsExactlyInAnyOrderElementsOf(components);
        assertThat(required).containsExactlyInAnyOrderElementsOf(components);
        assertThat(objectSchema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    private static void assertFailure(String modelOutput, Class<? extends ExtractionException> expected) {
        assertThatThrownBy(() -> extract(modelOutput)).isInstanceOf(expected);
    }
}
