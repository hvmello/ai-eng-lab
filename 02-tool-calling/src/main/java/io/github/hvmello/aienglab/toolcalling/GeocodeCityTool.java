package io.github.hvmello.aienglab.toolcalling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hvmello.aienglab.shared.ToolSpec;

/** Looks a city up by name and returns candidate places with coordinates. Needed before a forecast. */
public final class GeocodeCityTool implements Tool<GeocodeCityTool.Args> {

    static final int DEFAULT_COUNT = 3;

    /** Arguments. {@code count} is optional and defaults to 3. */
    public record Args(String name, Integer count) {
        public Args {
            new Arguments.Checks()
                    .require(name != null && !name.isBlank(), "name", "is required and must not be blank")
                    .require(name == null || name.length() <= 100, "name", "must be at most 100 characters")
                    .require(count == null || (count >= 1 && count <= 10), "count", "must be between 1 and 10")
                    .verify();
            count = count == null ? DEFAULT_COUNT : count;
        }
    }

    private static final ToolSpec SPEC = new ToolSpec(
            "geocode_city",
            "Finds places by city name and returns their latitude and longitude. Call this first when you only "
                    + "know a city name.",
            "{\"type\":\"object\",\"properties\":{"
                    + "\"name\":{\"type\":\"string\",\"description\":\"City name, for example Porto Alegre\"},"
                    + "\"count\":{\"type\":\"integer\",\"description\":\"How many candidates, 1 to 10, default 3\"}},"
                    + "\"required\":[\"name\"]}");

    private final OpenMeteoClient api;

    public GeocodeCityTool(OpenMeteoClient api) {
        this.api = api;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Args parse(JsonNode arguments) {
        return Arguments.parse(arguments, Args.class);
    }

    @Override
    public JsonNode execute(Args args) throws ToolFailure {
        JsonNode response = api.geocode(args.name(), args.count());
        ArrayNode matches = JsonNodeFactory.instance.arrayNode();
        for (JsonNode place : response.path("results")) {
            ObjectNode match = matches.addObject();
            match.put("name", place.path("name").asText(""));
            match.put("region", place.path("admin1").asText(""));
            match.put("country", place.path("country").asText(""));
            match.set("latitude", number(place.path("latitude")));
            match.set("longitude", number(place.path("longitude")));
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("matches", matches);
        return out;
    }

    static JsonNode number(JsonNode node) {
        return node.isNumber() ? node : JsonNodeFactory.instance.nullNode();
    }
}
