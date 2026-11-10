package io.github.hvmello.aienglab.toolcalling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal client for the public Open-Meteo APIs (no key). Timeouts are set on every request, the response size is
 * capped, query values are URL-encoded, and failures become {@link ToolFailure} with messages that carry no URL,
 * host or upstream body. Base URLs are injectable so tests never touch the network.
 */
public final class OpenMeteoClient {

    public static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com";
    public static final String FORECAST_URL = "https://api.open-meteo.com";
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String geocodingBase;
    private final String forecastBase;
    private final Duration requestTimeout;
    private final int maxBytes;

    public OpenMeteoClient(String geocodingBase, String forecastBase, Duration requestTimeout, int maxBytes) {
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        this.geocodingBase = stripSlash(geocodingBase);
        this.forecastBase = stripSlash(forecastBase);
        this.requestTimeout = requestTimeout;
        this.maxBytes = maxBytes;
    }

    /** Talks to the real public APIs with a 5 second request timeout. */
    public static OpenMeteoClient publicApi() {
        return new OpenMeteoClient(GEOCODING_URL, FORECAST_URL, Duration.ofSeconds(5), MAX_RESPONSE_BYTES);
    }

    JsonNode geocode(String name, int count) throws ToolFailure {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("name", name);
        query.put("count", Integer.toString(count));
        query.put("language", "en");
        query.put("format", "json");
        return get(geocodingBase + "/v1/search", query);
    }

    JsonNode forecast(String latitude, String longitude, int days) throws ToolFailure {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("latitude", latitude);
        query.put("longitude", longitude);
        query.put("daily", "temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max");
        query.put("timezone", "auto");
        query.put("forecast_days", Integer.toString(days));
        return get(forecastBase + "/v1/forecast", query);
    }

    private JsonNode get(String base, Map<String, String> query) throws ToolFailure {
        StringBuilder url = new StringBuilder(base);
        char separator = '?';
        for (Map.Entry<String, String> e : query.entrySet()) {
            url.append(separator)
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status >= 500) {
                    throw new ToolFailure(
                            "The weather service had an error (HTTP " + status + "). Retrying may work.", true);
                }
                if (status == 429) {
                    throw new ToolFailure(
                            "The weather service is rate limiting requests. Retrying later may work.", true);
                }
                if (status >= 400) {
                    throw new ToolFailure(
                            "The weather service rejected the request (HTTP " + status + "). Check the arguments; "
                                    + "repeating the same call will not help.",
                            false);
                }
                byte[] bytes = body.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new ToolFailure("The weather service answer was too large to use.", false);
                }
                return JSON.readTree(bytes);
            }
        } catch (JsonProcessingException e) {
            throw new ToolFailure("The weather service sent an answer that could not be read.", false);
        } catch (HttpTimeoutException e) {
            throw new ToolFailure("The weather service did not answer in time. Retrying may work.", true);
        } catch (IOException e) {
            throw new ToolFailure("The weather service could not be reached. Retrying may work.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("The weather request was interrupted.", true);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
