package io.github.hvmello.aienglab.mcpserver;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * Validates a reference access token against the authorization record, in process. One database
 * lookup per call, so a revoked or deleted authorization stops working on the very next request.
 *
 * <p>It is both the resource server's {@link OpaqueTokenIntrospector} and the check the tool
 * layer repeats on every {@code read_document} page.
 */
public class AccessTokenValidator implements OpaqueTokenIntrospector {

  /** What a valid token stands for. */
  public record Grant(
      String authorizationId, String clientId, String principal, Set<String> scopes) {
    public boolean hasScope(String scope) {
      return scopes.contains(scope);
    }
  }

  private final OAuth2AuthorizationService authorizations;
  private final Clock clock;

  public AccessTokenValidator(OAuth2AuthorizationService authorizations, Clock clock) {
    this.authorizations = authorizations;
    this.clock = clock;
  }

  /** @throws BadOpaqueTokenException when the token is unknown, revoked or expired */
  public Grant validate(String tokenValue) {
    if (tokenValue == null || tokenValue.isBlank()) {
      throw new BadOpaqueTokenException("missing token");
    }
    OAuth2Authorization authorization =
        authorizations.findByToken(tokenValue, OAuth2TokenType.ACCESS_TOKEN);
    if (authorization == null) {
      throw new BadOpaqueTokenException("unknown token");
    }
    OAuth2Authorization.Token<OAuth2AccessToken> token = authorization.getAccessToken();
    if (token == null || token.isInvalidated()) {
      throw new BadOpaqueTokenException("revoked token");
    }
    Instant now = clock.instant();
    Instant expiresAt = token.getToken().getExpiresAt();
    if (expiresAt == null || !now.isBefore(expiresAt)) {
      throw new BadOpaqueTokenException("expired token");
    }
    Instant issuedAt = token.getToken().getIssuedAt();
    if (issuedAt != null && issuedAt.isAfter(now.plusSeconds(60))) {
      throw new BadOpaqueTokenException("token issued in the future");
    }
    return new Grant(
        authorization.getId(),
        authorization.getRegisteredClientId(),
        authorization.getPrincipalName(),
        Set.copyOf(token.getToken().getScopes()));
  }

  @Override
  public OAuth2AuthenticatedPrincipal introspect(String token) {
    Grant grant = validate(token);
    Collection<GrantedAuthority> authorities =
        grant.scopes().stream()
            .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
            .collect(Collectors.toList());
    Map<String, Object> attributes =
        Map.of(
            "sub", grant.principal(),
            "client_id", grant.clientId(),
            "scope", String.join(" ", grant.scopes()),
            "active", true);
    return new OAuth2IntrospectionAuthenticatedPrincipal(grant.principal(), attributes, authorities);
  }
}
