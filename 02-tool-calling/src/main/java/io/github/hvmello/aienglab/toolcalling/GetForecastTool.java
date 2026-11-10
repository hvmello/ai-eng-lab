package io.github.hvmello.aienglab.toolcalling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hvmello.aienglab.shared.ToolSpec;
import java.math.BigDecimal;

/** Daily forecast for a coordinate. Needs the latitude and longitude that {@code geocode_city} returns. */
public final class GetForecastTool implements Tool<GetForecastTool.Args> {

    static final int DEFAULT_DAYS = 3;

    /** Arguments. {@code days} is optional and defaults to 3. */
    public record Args(Double latitude, Double longitude, Integer days) {
        public Args {
            new Arguments.Checks()
                    .require(latitude != null, "latitude", "is required")
                    .require(latitude == null || (latitude >= -90 && latitude <= 90), "latitude",
                            "must be between -90 and 90")
                    .require(longitude != null, "longitude", "is required")
                    .require(longitude == null || (longitude >= -180 && longitude <= 180), "longitude",
                            "must be between -180 and 180")
                    .require(days == null || (days >= 1 && days <= 7), "days", "must be between 1 and 7")
                    .verify();
            days = days == null ? DEFAULT_DAYS : days;
        }
    }

    private static final ToolSpec SPEC = new ToolSpec(
            "get_forecast",
            "Daily weather forecast (temperatures in Celsius, precipitation in mm and probability in percent) for "
                    + "a latitude and longitude. The first day is today.",
            "{\"type\":\"object\",\"properties\":{"
                    + "\"latitude\":{\"type\":\"number\",\"description\":\"Latitude, -90 to 90\"},"
                    + "\"longitude\":{\"type\":\"number\",\"description\":\"Longitude, -180 to 180\"},"
                    + "\"days\":{\"type\":\"integer\",\"description\":\"Days to forecast, 1 to 7, default 3\"}},"
                    + "\"required\":[\"latitude\",\"longitude\"]}");

    private final OpenMeteoClient api;

    public GetForecastTool(OpenMeteoClient api) {
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
        JsonNode response = api.forecast(plain(args.latitude()), plain(args.longitude()), args.days());
        JsonNode daily = response.path("daily");
        JsonNode dates = daily.path("time");
        if (!dates.isArray()) {
            throw new ToolFailure("The weather service answer had no forecast in it.", false);
        }
        ArrayNode days = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < dates.size(); i++) {
            ObjectNode day = days.addObject();
            day.put("date", dates.get(i).asText(""));
            day.set("temp_max_c", at(daily, "temperature_2m_max", i));
            day.set("temp_min_c", at(daily, "temperature_2m_min", i));
            day.set("precipitation_mm", at(daily, "precipitation_sum", i));
            day.set("precipitation_probability_percent", at(daily, "precipitation_probability_max", i));
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("days", days);
        return out;
    }

    private static JsonNode at(JsonNode daily, String field, int index) {
        return GeocodeCityTool.number(daily.path(field).path(index));
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).toPlainString();
    }
}
