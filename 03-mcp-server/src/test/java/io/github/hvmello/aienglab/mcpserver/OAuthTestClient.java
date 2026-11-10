package io.github.hvmello.aienglab.mcpserver;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A scripted OAuth and MCP client: a browser session for the user, plain HTTP for the rest. */
final class OAuthTestClient {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
  private static final Pattern STATE = Pattern.compile("name=\"state\"[^>]*value=\"([^\"]+)\"");

  /** An HTTP response with the body parsed on demand. */
  record Reply(int status, String body, HttpResponse<String> raw) {
    JsonNode json() {
      return JSON.readTree(body);
    }

    String header(String name) {
      return raw.headers().firstValue(name).orElse(null);
    }
  }

  /** A PKCE pair. */
  record Pkce(String verifier, String challenge) {
    static Pkce create() {
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      try {
        byte[] hash =
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** A token response. */
  record Tokens(String accessToken, String refreshToken, String scope) {}

  private final String base;
  private final String user;
  private final String password;
  private final HttpClient http;

  OAuthTestClient(String base, String user, String password) {
    this.base = base;
    this.user = user;
    this.password = password;
    this.http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** A client without the user's browser session. */
  static HttpClient anonymous() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  String base() {
    return base;
  }

  // ---- plain requests -------------------------------------------------------------------

  Reply get(String url, String... headers) {
    return send(request(url, headers).GET().build());
  }

  Reply postForm(String url, Map<String, String> form, String... headers) {
    return send(
        request(url, headers)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
            .build());
  }

  Reply postJson(String url, String json, String... headers) {
    return send(
        request(url, headers)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build());
  }

  private HttpRequest.Builder request(String url, String... headers) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(absolute(url)));
    for (int i = 0; i + 1 < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    return builder;
  }

  private Reply send(HttpRequest request) {
    return send(http, request);
  }

  static Reply send(HttpClient client, HttpRequest request) {
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return new Reply(response.statusCode(), response.body(), response);
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  String absolute(String url) {
    return url.startsWith("http") ? url : base + url;
  }

  static String encode(Map<String, String> form) {
    return form.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  // ---- registration ---------------------------------------------------------------------

  static String registrationJson(String redirectUri, String scope) {
    return "{\"client_name\":\"IT client\",\"redirect_uris\":[\""
        + redirectUri
        + "\"],\"token_endpoint_auth_method\":\"none\","
        + "\"grant_types\":[\"authorization_code\",\"refresh_token\"],\"response_types\":[\"code\"],"
        + "\"scope\":\""
        + scope
        + "\"}";
  }

  Reply register(String redirectUri, String scope) {
    return postJson("/register", registrationJson(redirectUri, scope));
  }

  String registerClient(String redirectUri) {
    Reply reply = register(redirectUri, "docs.read");
    if (reply.status() != 201) {
      throw new IllegalStateException("registration failed: " + reply.status() + " " + reply.body());
    }
    return reply.json().get("client_id").asString();
  }

  // ---- authorization code flow ----------------------------------------------------------

  String authorizeUrl(String clientId, String redirectUri, Pkce pkce, String scope, String... extra) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("response_type", "code");
    params.put("client_id", clientId);
    params.put("redirect_uri", redirectUri);
    params.put("scope", scope);
    params.put("state", "client-state-123");
    if (pkce != null) {
      params.put("code_challenge", pkce.challenge());
      params.put("code_challenge_method", "S256");
    }
    for (int i = 0; i + 1 < extra.length; i += 2) {
      params.put(extra[i], extra[i + 1]);
    }
    return base + "/oauth2/authorize?" + encode(params);
  }

  /**
   * Runs the browser part: the authorize request, login, consent. Returns the final redirect
   * location, either {@code redirect_uri?code=...} or {@code redirect_uri?error=...}.
   */
  String authorizeAndConsent(String authorizeUrl) {
    return walkAuthorize(authorizeUrl, false);
  }

  /** Runs the same walk but stops at the consent screen and returns its HTML. */
  String consentPage(String authorizeUrl) {
    return walkAuthorize(authorizeUrl, true);
  }

  private String walkAuthorize(String authorizeUrl, boolean stopAtConsent) {
    Reply reply = get(authorizeUrl);
    List<String> trail = new ArrayList<>();
    for (int hops = 0; hops < 8; hops++) {
      trail.add(reply.status() + " " + reply.header("Location"));
      if (reply.status() / 100 == 3) {
        String location = reply.header("Location");
        if (!location.startsWith(base) && !location.startsWith("/")) {
          return location; // left the server: this is the redirect to the client
        }
        if (location.contains("/oauth2/authorize") && hops > 0 && location.contains("code=")) {
          return location;
        }
        reply = get(location);
      } else if (reply.body() != null && reply.body().contains("name=\"username\"")) {
        reply =
            postForm(
                "/login",
                Map.of("username", user, "password", password, "_csrf", find(CSRF, reply.body())));
      } else if (reply.body() != null && reply.body().contains("Authorize access")) {
        if (stopAtConsent) {
          return reply.body();
        }
        reply =
            postForm(
                "/oauth2/authorize",
                Map.of(
                    "client_id", find(Pattern.compile("name=\"client_id\"[^>]*value=\"([^\"]+)\""), reply.body()),
                    "state", find(STATE, reply.body()),
                    "scope", "docs.read",
                    "_csrf", find(CSRF, reply.body())));
      } else {
        throw new IllegalStateException(
            "unexpected response " + reply.status() + ": " + abbreviate(reply.body()));
      }
    }
    throw new IllegalStateException("too many redirects: " + trail);
  }

  Map<String, String> query(String location) {
    String q = URI.create(location).getRawQuery();
    Map<String, String> out = new LinkedHashMap<>();
    if (q != null) {
      for (String pair : q.split("&")) {
        String[] kv = pair.split("=", 2);
        out.put(
            java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
            kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
      }
    }
    return out;
  }

  String obtainCode(String clientId, String redirectUri, Pkce pkce) {
    String location =
        authorizeAndConsent(authorizeUrl(clientId, redirectUri, pkce, "docs.read"));
    Map<String, String> q = query(location);
    if (!q.containsKey("code")) {
      throw new IllegalStateException("no code in " + location);
    }
    return q.get("code");
  }

  Reply exchangeCode(String clientId, String redirectUri, String code, String verifier, String... extra) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    form.put("client_id", clientId);
    form.put("code_verifier", verifier);
    for (int i = 0; i + 1 < extra.length; i += 2) {
      form.put(extra[i], extra[i + 1]);
    }
    return postForm("/oauth2/token", form);
  }

  Reply refresh(String clientId, String refreshToken, String... extra) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    form.put("client_id", clientId);
    for (int i = 0; i + 1 < extra.length; i += 2) {
      form.put(extra[i], extra[i + 1]);
    }
    return postForm("/oauth2/token", form);
  }

  Reply revoke(String clientId, String token, String hint) {
    return postForm(
        "/oauth2/revoke", Map.of("client_id", clientId, "token", token, "token_type_hint", hint));
  }

  /** Registers a client and completes the whole flow. */
  Session signIn(String redirectUri) {
    String clientId = registerClient(redirectUri);
    Pkce pkce = Pkce.create();
    String code = obtainCode(clientId, redirectUri, pkce);
    Reply reply = exchangeCode(clientId, redirectUri, code, pkce.verifier());
    if (reply.status() != 200) {
      throw new IllegalStateException("token exchange failed: " + reply.status() + " " + reply.body());
    }
    return new Session(clientId, redirectUri, tokens(reply));
  }

  static Tokens tokens(Reply reply) {
    JsonNode json = reply.json();
    return new Tokens(
        json.get("access_token").asString(),
        json.has("refresh_token") ? json.get("refresh_token").asString() : null,
        json.has("scope") ? json.get("scope").asString() : null);
  }

  /** A signed-in client. */
  record Session(String clientId, String redirectUri, Tokens tokens) {}

  // ---- MCP ------------------------------------------------------------------------------

  Reply mcp(String accessToken, String method, String paramsJson, String... headers) {
    return send(mcpRequest(accessToken, method, paramsJson, headers));
  }

  HttpRequest mcpRequest(String accessToken, String method, String paramsJson, String... headers) {
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
            + method
            + "\""
            + (paramsJson == null ? "" : ",\"params\":" + paramsJson)
            + "}";
    HttpRequest.Builder builder =
        request("/mcp", headers)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (accessToken != null) {
      builder.header("Authorization", "Bearer " + accessToken);
    }
    return builder.build();
  }

  Reply callTool(String accessToken, String tool, String argumentsJson) {
    return mcp(
        accessToken,
        "tools/call",
        "{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}");
  }

  /** The JSON payload a tool put in its text content. */
  static JsonNode toolPayload(Reply reply) {
    JsonNode result = reply.json().get("result");
    return JSON.readTree(result.get("content").get(0).get("text").asString());
  }

  static boolean toolFailed(Reply reply) {
    return reply.json().get("result").get("isError").asBoolean();
  }

  static List<String> toolNames(Reply reply) {
    List<String> names = new java.util.ArrayList<>();
    reply.json().get("result").get("tools").forEach(t -> names.add(t.get("name").asString()));
    return names;
  }

  // ---- helpers --------------------------------------------------------------------------

  private static String find(Pattern pattern, String body) {
    Matcher m = pattern.matcher(body);
    if (!m.find()) {
      throw new IllegalStateException("no match for " + pattern + " in " + abbreviate(body));
    }
    return m.group(1).replace("&#61;", "=").replace("&amp;", "&");
  }

  private static String abbreviate(String s) {
    return s == null ? "null" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
  }
}
