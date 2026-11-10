package io.github.hvmello.aienglab.mcpserver;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Opaque refresh tokens for every client, public ones included. Spring's generator skips public
 * clients on the authorization code grant, but an MCP client is public and needs to refresh its
 * short-lived access token without sending the user through the browser again.
 *
 * <p>The risk of a refresh token held by a public client is a copied token. That is covered by
 * rotation on every use and by revoking the whole token family when a retired token is replayed
 * (see {@link RefreshTokenReplayGuard}).
 */
public class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

  private final StringKeyGenerator keys =
      new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);
  private final Clock clock;

  public PublicClientRefreshTokenGenerator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public OAuth2RefreshToken generate(OAuth2TokenContext context) {
    if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
      return null;
    }
    Instant issuedAt = clock.instant();
    Instant expiresAt =
        issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
    return new OAuth2RefreshToken(keys.generateKey(), issuedAt, expiresAt);
  }
}
