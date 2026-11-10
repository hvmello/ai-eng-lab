package io.github.hvmello.aienglab.mcpserver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * Detects replay of a rotated refresh token.
 *
 * <p>It decorates the authorization service. When a save replaces the refresh token of an
 * authorization, the SHA-256 of the retired token is stored. Looking up a token that is gone but
 * is on that list means somebody presented a copy, so the whole authorization (the token family,
 * including its current access token) is deleted and the lookup returns nothing, which the token
 * endpoint answers with {@code invalid_grant}.
 */
public class RefreshTokenReplayGuard implements OAuth2AuthorizationService {

  private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenReplayGuard.class);

  private final OAuth2AuthorizationService delegate;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public RefreshTokenReplayGuard(
      OAuth2AuthorizationService delegate, JdbcTemplate jdbc, Clock clock) {
    this.delegate = delegate;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    OAuth2Authorization previous = delegate.findById(authorization.getId());
    if (previous != null && previous.getRefreshToken() != null) {
      String old = previous.getRefreshToken().getToken().getTokenValue();
      OAuth2Authorization.Token<OAuth2RefreshToken> current = authorization.getRefreshToken();
      if (current == null || !old.equals(current.getToken().getTokenValue())) {
        jdbc.update(
            "INSERT INTO retired_refresh_token (token_sha256, authorization_id, retired_at)"
                + " VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            sha256(old),
            authorization.getId(),
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
      }
    }
    delegate.save(authorization);
  }

  @Override
  public void remove(OAuth2Authorization authorization) {
    delegate.remove(authorization);
  }

  @Override
  public OAuth2Authorization findById(String id) {
    return delegate.findById(id);
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
    OAuth2Authorization found = delegate.findByToken(token, tokenType);
    if (found != null || !mayBeRefreshToken(tokenType)) {
      return found;
    }
    String authorizationId = retiredFamily(token);
    if (authorizationId != null) {
      OAuth2Authorization family = delegate.findById(authorizationId);
      if (family != null) {
        LOG.warn("Retired refresh token replayed: revoking authorization {}", authorizationId);
        delegate.remove(family);
      }
    }
    return null;
  }

  private String retiredFamily(String token) {
    var ids =
        jdbc.queryForList(
            "SELECT authorization_id FROM retired_refresh_token WHERE token_sha256 = ?",
            String.class,
            sha256(token));
    return ids.isEmpty() ? null : ids.get(0);
  }

  private static boolean mayBeRefreshToken(OAuth2TokenType type) {
    return type == null || OAuth2TokenType.REFRESH_TOKEN.equals(type);
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
