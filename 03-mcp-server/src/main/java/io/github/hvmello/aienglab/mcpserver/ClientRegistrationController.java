package io.github.hvmello.aienglab.mcpserver;

import io.github.hvmello.aienglab.mcpserver.ClientRegistrationPolicy.InvalidMetadataException;
import io.github.hvmello.aienglab.mcpserver.ClientRegistrationPolicy.Registration;
import io.github.hvmello.aienglab.mcpserver.RegistrationLimiter.LimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 7591 dynamic client registration, for public clients only. Spring's built-in endpoint needs
 * an initial access token, which an MCP client does not have (see ADR 0003).
 */
@RestController
public class ClientRegistrationController {

  private final ClientRegistrationPolicy policy;
  private final RegistrationLimiter limiter;
  private final McpServerProperties properties;
  private final Clock clock;

  public ClientRegistrationController(
      ClientRegistrationPolicy policy,
      RegistrationLimiter limiter,
      McpServerProperties properties,
      Clock clock) {
    this.policy = policy;
    this.limiter = limiter;
    this.properties = properties;
    this.clock = clock;
  }

  @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> register(
      @RequestBody Map<String, Object> metadata, HttpServletRequest request) {
    Registration registration;
    try {
      registration = policy.validate(metadata);
    } catch (InvalidMetadataException e) {
      return error(HttpStatus.BAD_REQUEST, "invalid_client_metadata", e.getMessage());
    }
    try {
      // The remote address is the TCP peer. The server binds to loopback and sits behind no
      // proxy, so X-Forwarded-For is deliberately not trusted.
      RegisteredClient client =
          limiter.register(request.getRemoteAddr(), () -> toClient(registration));
      return ResponseEntity.status(HttpStatus.CREATED)
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .contentType(MediaType.APPLICATION_JSON)
          .body(response(client, registration));
    } catch (LimitExceededException e) {
      return error(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", e.getMessage());
    }
  }

  private RegisteredClient toClient(Registration registration) {
    RegisteredClient.Builder builder =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(UUID.randomUUID().toString())
            .clientIdIssuedAt(clock.instant())
            .clientName(registration.clientName())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUris(uris -> uris.addAll(registration.redirectUris()))
            .scopes(scopes -> scopes.addAll(registration.scopes()))
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(true)
                    .build())
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                    .accessTokenTimeToLive(properties.accessTokenTtl())
                    .refreshTokenTimeToLive(properties.refreshTokenTtl())
                    .reuseRefreshTokens(false)
                    .build());
    if (registration.grantTypes().contains("refresh_token")) {
      builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
    }
    return builder.build();
  }

  private static Map<String, Object> response(RegisteredClient client, Registration registration) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("client_id", client.getClientId());
    body.put("client_id_issued_at", client.getClientIdIssuedAt().getEpochSecond());
    body.put("client_name", client.getClientName());
    body.put("redirect_uris", List.copyOf(client.getRedirectUris()));
    body.put("grant_types", List.copyOf(registration.grantTypes()));
    body.put("response_types", List.of("code"));
    body.put("token_endpoint_auth_method", "none");
    body.put("scope", String.join(" ", client.getScopes()));
    return body;
  }

  private static ResponseEntity<Map<String, Object>> error(
      HttpStatus status, String code, String description) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("error_description", description);
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }
}
