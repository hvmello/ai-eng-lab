package io.github.hvmello.aienglab.toolcalling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Strict conversion of untrusted JSON into an argument record: unknown fields, wrong types and string-for-number
 * coercions are rejected, and the problems are reported by field path without leaking class names.
 */
public final class Arguments {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    static {
        // A number or boolean is not a string: {"text":5} must be reported, not silently turned into "5".
        var text = MAPPER.coercionConfigFor(LogicalType.Textual);
        text.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        text.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        text.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    private Arguments() {}

    public static <A> A parse(JsonNode node, Class<A> type) {
        if (node == null || !node.isObject()) {
            throw new InvalidArgumentsException("", "arguments must be a JSON object");
        }
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw translate(e);
        }
    }

    private static InvalidArgumentsException translate(JsonProcessingException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InvalidArgumentsException invalid) {
                return invalid;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        String path = e instanceof MismatchedInputException mismatch ? pathOf(mismatch) : "";
        if (e instanceof UnrecognizedPropertyException) {
            return new InvalidArgumentsException(path, "is not a known field");
        }
        if (e instanceof InvalidFormatException format) {
            return new InvalidArgumentsException(path, "must be " + describe(format.getTargetType()));
        }
        if (e instanceof MismatchedInputException mismatch) {
            return new InvalidArgumentsException(path, "must be " + describe(mismatch.getTargetType()));
        }
        return new InvalidArgumentsException("", "arguments could not be read");
    }

    private static String pathOf(MismatchedInputException e) {
        return e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static String describe(Class<?> target) {
        if (target == null) {
            return "a valid value";
        }
        if (target == Integer.class || target == int.class || target == Long.class || target == long.class) {
            return "an integer";
        }
        if (Number.class.isAssignableFrom(target) || target == double.class || target == float.class) {
            return "a number";
        }
        if (target == String.class) {
            return "a string";
        }
        if (target == Boolean.class || target == boolean.class) {
            return "a boolean";
        }
        return "a valid value";
    }

    /** Collects problems while a record checks its fields, then throws them all at once. */
    public static final class Checks {

        private final List<FieldProblem> problems = new ArrayList<>();

        public Checks require(boolean ok, String path, String problem) {
            if (!ok) {
                problems.add(new FieldProblem(path, problem));
            }
            return this;
        }

        /** Throws when any check failed. */
        public void verify() {
            if (!problems.isEmpty()) {
                throw new InvalidArgumentsException(problems);
            }
        }
    }
}
