package io.github.hvmello.aienglab.llmbasics;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hvmello.aienglab.llmbasics.ExtractionException.InvalidValue;
import io.github.hvmello.aienglab.llmbasics.ExtractionException.MalformedJson;
import io.github.hvmello.aienglab.llmbasics.ExtractionException.SchemaMismatch;
import io.github.hvmello.aienglab.llmbasics.ExtractionException.TruncatedOutput;
import io.github.hvmello.aienglab.shared.ChatParameters;
import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.FinishReason;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.Message;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.Objects;

/**
 * Extracts an {@link Invoice} from free text.
 *
 * <p>Pipeline: call the model, check the finish reason, parse with Jackson in strict mode, validate the values.
 * Each stage fails with its own {@link ExtractionException} subtype. There are no retries here.
 *
 * <p>Validation catches values that are wrong in a way the document itself can show (a total that does not match
 * its lines). It cannot catch a value that is wrong but internally consistent, such as every price scaled by ten
 * together with the total.
 */
public final class InvoiceExtractor {

    private static final String SYSTEM_PROMPT = "You extract invoices from text. Answer with one JSON object that "
            + "follows the given schema and nothing else. Use only values that appear in the text. Amounts are "
            + "plain numbers without currency symbols or thousands separators.";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final LlmClient client;
    private final ChatParameters parameters;
    private final String schema;

    public InvoiceExtractor(LlmClient client) {
        this(client, ChatParameters.none());
    }

    public InvoiceExtractor(LlmClient client, ChatParameters parameters) {
        this.client = Objects.requireNonNull(client, "client");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.schema = loadSchema();
    }

    /** The JSON schema sent to the model. */
    public static String schema() {
        return loadSchema();
    }

    /**
     * @throws ExtractionException when the output is cut off, not JSON, the wrong shape, or has invalid values
     * @throws io.github.hvmello.aienglab.shared.LlmCallException when the provider call itself fails
     */
    public Invoice extract(String text) {
        Objects.requireNonNull(text, "text");
        ChatRequest request = ChatRequest.of(Message.system(SYSTEM_PROMPT), Message.user(text))
                .withParameters(parameters)
                .withJsonSchema(schema);
        ChatResult result = client.chat(request);
        return parseAndValidate(result);
    }

    private static final int MAX_SCALE = 10;
    private static final int MAX_INTEGER_DIGITS = 18;

    static Invoice parseAndValidate(ChatResult result) {
        String raw = result.text() == null ? "" : result.text();
        if (result.finishReason() == FinishReason.LENGTH) {
            throw new TruncatedOutput(raw);
        }
        if (raw.isBlank()) {
            throw new MalformedJson("Model returned no text", raw, null);
        }
        // Two steps, so that "not JSON" and "JSON of the wrong shape" stay separate failures.
        JsonNode tree;
        try {
            tree = MAPPER.readTree(raw);
        } catch (JsonParseException e) {
            throw new MalformedJson("Model output is not valid JSON: " + e.getOriginalMessage(), raw, e);
        } catch (JsonProcessingException e) {
            throw new MalformedJson("Model output is not a single JSON value: " + e.getOriginalMessage(), raw, e);
        }
        Invoice invoice;
        try {
            invoice = MAPPER.treeToValue(tree, Invoice.class);
        } catch (JsonProcessingException e) {
            throw new SchemaMismatch("JSON does not match the invoice schema: " + e.getOriginalMessage(), raw, e);
        }
        if (invoice == null) {
            throw new SchemaMismatch("JSON is null, expected an invoice object", raw, null);
        }
        validate(invoice, raw);
        return invoice;
    }

    private static void validate(Invoice invoice, String raw) {
        requirePresent(invoice.vendor(), "vendor", raw);
        requirePresent(invoice.currency(), "currency", raw);
        requirePresent(invoice.issueDate(), "issueDate", raw);
        requirePresent(invoice.lineItems(), "lineItems", raw);
        requirePresent(invoice.total(), "total", raw);
        for (int i = 0; i < invoice.lineItems().size(); i++) {
            Invoice.LineItem item = invoice.lineItems().get(i);
            requirePresent(item, "lineItems[" + i + "]", raw);
            requirePresent(item.description(), "lineItems[" + i + "].description", raw);
            requirePresent(item.quantity(), "lineItems[" + i + "].quantity", raw);
            requirePresent(item.unitPrice(), "lineItems[" + i + "].unitPrice", raw);
        }

        if (invoice.vendor().isBlank()) {
            throw new InvalidValue("vendor is blank", raw);
        }
        if (!isIsoCurrency(invoice.currency())) {
            throw new InvalidValue("currency is not an ISO 4217 code: " + invoice.currency(), raw);
        }
        if (invoice.lineItems().isEmpty()) {
            throw new InvalidValue("lineItems is empty", raw);
        }
        // Range-check every amount before any arithmetic: a huge exponent such as 1e-500000000 is a short
        // BigDecimal, but adding it forces a number with hundreds of millions of digits.
        requireReasonableAmount(invoice.total(), "total", raw);
        for (int i = 0; i < invoice.lineItems().size(); i++) {
            requireReasonableAmount(invoice.lineItems().get(i).unitPrice(), "lineItems[" + i + "].unitPrice", raw);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Invoice.LineItem item : invoice.lineItems()) {
            if (item.quantity() <= 0) {
                throw new InvalidValue("quantity must be positive: " + item.quantity(), raw);
            }
            if (item.unitPrice().signum() <= 0) {
                throw new InvalidValue("unitPrice must be positive: " + item.unitPrice(), raw);
            }
            sum = sum.add(item.amount());
        }
        if (invoice.total().signum() <= 0) {
            throw new InvalidValue("total must be positive: " + invoice.total(), raw);
        }
        if (sum.compareTo(invoice.total()) != 0) {
            throw new InvalidValue("line items sum to " + sum + " but total is " + invoice.total(), raw);
        }
    }

    private static void requireReasonableAmount(BigDecimal amount, String field, String raw) {
        if (amount.scale() > MAX_SCALE || (long) amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidValue(field + " is outside the supported range (scale " + amount.scale()
                    + ", precision " + amount.precision() + ")", raw);
        }
    }

    private static void requirePresent(Object value, String field, String raw) {
        if (value == null) {
            throw new SchemaMismatch("required field is missing or null: " + field, raw, null);
        }
    }

    private static boolean isIsoCurrency(String code) {
        if (code.length() != 3) {
            return false;
        }
        try {
            return Currency.getInstance(code).getCurrencyCode().equals(code);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String loadSchema() {
        try (InputStream in = InvoiceExtractor.class.getResourceAsStream("/invoice.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("invoice.schema.json is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
