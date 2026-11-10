package io.github.hvmello.aienglab.mcpserver;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rules for RFC 7591 dynamic registration. Only public clients that use the authorization code
 * flow with the one read scope are accepted; anything else is {@code invalid_client_metadata}.
 */
public class ClientRegistrationPolicy {

  /** A registration that passed every rule. */
  public record Registration(
      String clientName, List<String> redirectUris, Set<String> grantTypes, Set<String> scopes) {}

  /** A rejected registration, mapped to HTTP 400. */
  public static final class InvalidMetadataException extends RuntimeException {
    public InvalidMetadataException(String message) {
      super(message);
    }
  }

  private static final Set<String> ALLOWED_GRANTS = Set.of("authorization_code", "refresh_token");
  private static final Set<String> ALLOWED_SCOPES = Set.of(McpServerProperties.SCOPE);
  private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "[::1]", "localhost");
  private static final int MAX_REDIRECT_URIS = 5;
  private static final int MAX_URI_LENGTH = 190;

  public Registration validate(Map<String, Object> metadata) {
    String method = string(metadata, "token_endpoint_auth_method");
    if (!"none".equals(method)) {
      throw invalid("token_endpoint_auth_method must be none: only public clients are accepted");
    }

    Set<String> grants = stringSet(metadata, "grant_types", Set.of("authorization_code"));
    if (!ALLOWED_GRANTS.containsAll(grants) || !grants.contains("authorization_code")) {
      throw invalid("grant_types must include authorization_code and be within "
          + "{authorization_code, refresh_token}");
    }

    if (metadata.containsKey("response_types")) {
      Set<String> responses = stringSet(metadata, "response_types", Set.of());
      if (!Set.of("code").equals(responses)) {
        throw invalid("response_types must be [code]");
      }
    }

    Set<String> scopes = scopes(metadata);
    if (scopes.isEmpty() || !ALLOWED_SCOPES.containsAll(scopes)) {
      throw invalid("scope must be within {" + McpServerProperties.SCOPE + "}");
    }

    List<String> redirectUris = redirectUris(metadata);

    String name = "MCP client";
    Object rawName = metadata.get("client_name");
    if (rawName instanceof String s && !s.isBlank()) {
      name = s.strip();
      if (name.length() > 100) {
        throw invalid("client_name is longer than 100 characters");
      }
    } else if (rawName != null && !(rawName instanceof String)) {
      throw invalid("client_name must be a string");
    }
    return new Registration(name, redirectUris, grants, scopes);
  }

  private static List<String> redirectUris(Map<String, Object> metadata) {
    Object raw = metadata.get("redirect_uris");
    if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_REDIRECT_URIS) {
      throw invalid("redirect_uris must be a list of 1 to " + MAX_REDIRECT_URIS + " URIs");
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (!(o instanceof String uri) || uri.length() > MAX_URI_LENGTH) {
        throw invalid("every redirect URI must be a string of at most " + MAX_URI_LENGTH + " characters");
      }
      checkRedirectUri(uri);
      out.add(uri);
    }
    return List.copyOf(new LinkedHashSet<>(out));
  }

  /** https, or http on a loopback host (any port). No fragments, no user info. */
  static void checkRedirectUri(String value) {
    URI uri;
    try {
      uri = new URI(value);
    } catch (java.net.URISyntaxException e) {
      throw invalid("redirect URI is not a valid URI");
    }
    if (!uri.isAbsolute()) {
      throw invalid("redirect URI must be absolute");
    }
    if (uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
      throw invalid("redirect URI must not have a fragment or user info");
    }
    String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
    if (scheme.equals("https") && !host.isEmpty()) {
      return;
    }
    if (scheme.equals("http") && LOOPBACK_HOSTS.contains(host)) {
      return;
    }
    throw invalid("redirect URI must be https or http on a loopback host");
  }

  private static Set<String> scopes(Map<String, Object> metadata) {
    Object raw = metadata.get("scope");
    if (raw == null) {
      return Set.of(McpServerProperties.SCOPE);
    }
    if (!(raw instanceof String s)) {
      throw invalid("scope must be a space separated string");
    }
    Set<String> out = new LinkedHashSet<>();
    for (String part : s.trim().split("\\s+")) {
      if (!part.isEmpty()) {
        out.add(part);
      }
    }
    return out;
  }

  private static String string(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    return value instanceof String s ? s : null;
  }

  private static Set<String> stringSet(
      Map<String, Object> metadata, String key, Set<String> fallback) {
    Object raw = metadata.get(key);
    if (raw == null) {
      return fallback;
    }
    if (!(raw instanceof List<?> list)) {
      throw invalid(key + " must be an array of strings");
    }
    Set<String> out = new LinkedHashSet<>();
    for (Object o : list) {
      if (!(o instanceof String s)) {
        throw invalid(key + " must be an array of strings");
      }
      out.add(s);
    }
    return out;
  }

  private static InvalidMetadataException invalid(String message) {
    return new InvalidMetadataException(message);
  }
}
