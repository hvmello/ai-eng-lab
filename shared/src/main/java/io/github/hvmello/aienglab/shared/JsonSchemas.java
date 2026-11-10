package io.github.hvmello.aienglab.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Converts the JSON schema text of a tool into LangChain4j's schema model, which tool specifications require.
 * Supports the subset tools need: objects, arrays, strings, integers, numbers, booleans and string enums, with
 * descriptions and required lists. Anything else is rejected rather than silently dropped.
 */
final class JsonSchemas {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonSchemas() {}

    static JsonObjectSchema toObjectSchema(String schemaText) {
        JsonNode root;
        try {
            root = JSON.readTree(schemaText);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("tool schema is not valid JSON", e);
        }
        if (root == null || !root.isObject() || !"object".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("tool schema must be a JSON object schema with type=object");
        }
        return (JsonObjectSchema) convert(root);
    }

    private static JsonSchemaElement convert(JsonNode node) {
        String description = node.hasNonNull("description") ? node.get("description").asText() : null;
        if (node.has("enum")) {
            List<String> values = new ArrayList<>();
            node.get("enum").forEach(v -> values.add(v.asText()));
            return JsonEnumSchema.builder().description(description).enumValues(values).build();
        }
        String type = node.path("type").asText();
        return switch (type) {
            case "string" -> JsonStringSchema.builder().description(description).build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> {
                if (!node.has("items")) {
                    throw new IllegalArgumentException("array schema needs items");
                }
                yield JsonArraySchema.builder().description(description).items(convert(node.get("items"))).build();
            }
            case "object" -> {
                JsonObjectSchema.Builder builder = JsonObjectSchema.builder().description(description);
                Iterator<Map.Entry<String, JsonNode>> fields = node.path("properties").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    builder.addProperty(field.getKey(), convert(field.getValue()));
                }
                List<String> required = new ArrayList<>();
                node.path("required").forEach(r -> required.add(r.asText()));
                if (!required.isEmpty()) {
                    builder.required(required);
                }
                if (node.has("additionalProperties") && node.get("additionalProperties").isBoolean()) {
                    builder.additionalProperties(node.get("additionalProperties").asBoolean());
                }
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("unsupported schema type: " + type);
        };
    }
}
