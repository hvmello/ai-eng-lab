package io.github.hvmello.aienglab.mcpserver;

import java.net.URI;
import java.util.Locale;
import java.util.function.Consumer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;

/**
 * Redirect URI and scope validation for the authorization request.
 *
 * <p>Spring accepts any port only for IP literals ({@code 127.0.0.1}, {@code [::1]}) and demands an
 * exact match for {@code localhost}. Claude Code uses {@code localhost} with a port picked at run
 * time, so this validator treats {@code localhost} like the IP literals: scheme, host, path and
 * query must match a registered URI and only the port may differ. Everything else, and the scope
 * check, is Spring's own validation.
 */
public final class LoopbackRedirectValidator
    implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

  @Override
  public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
    OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
    String requested = request.getRedirectUri();
    if (requested != null && isLocalhost(requested)) {
      boolean match =
          context.getRegisteredClient().getRedirectUris().stream()
              .anyMatch(registered -> samePortless(registered, requested));
      if (!match) {
        throw reject(request);
      }
    } else {
      OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR.accept(
          context);
    }
    OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
  }

  static boolean isLocalhost(String uri) {
    try {
      URI parsed = new URI(uri);
      return "http".equalsIgnoreCase(parsed.getScheme())
          && parsed.getRawFragment() == null
          && "localhost".equalsIgnoreCase(parsed.getHost());
    } catch (java.net.URISyntaxException e) {
      return false;
    }
  }

  static boolean samePortless(String registered, String requested) {
    try {
      URI a = new URI(registered);
      URI b = new URI(requested);
      return a.getScheme() != null
          && a.getHost() != null
          && a.getScheme().equalsIgnoreCase(b.getScheme())
          && a.getHost().toLowerCase(Locale.ROOT).equals(b.getHost().toLowerCase(Locale.ROOT))
          && java.util.Objects.equals(a.getRawPath(), b.getRawPath())
          && java.util.Objects.equals(a.getRawQuery(), b.getRawQuery());
    } catch (java.net.URISyntaxException e) {
      return false;
    }
  }

  private static OAuth2AuthorizationCodeRequestAuthenticationException reject(
      OAuth2AuthorizationCodeRequestAuthenticationToken request) {
    OAuth2Error error =
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_REQUEST, "OAuth 2.0 Parameter: redirect_uri", null);
    // No redirect URI in the token: the error is shown, never sent to an unverified address.
    Authentication withoutRedirect =
        new OAuth2AuthorizationCodeRequestAuthenticationToken(
            request.getAuthorizationUri(),
            request.getClientId(),
            (Authentication) request.getPrincipal(),
            null,
            request.getState(),
            request.getScopes(),
            request.getAdditionalParameters());
    return new OAuth2AuthorizationCodeRequestAuthenticationException(
        error, (OAuth2AuthorizationCodeRequestAuthenticationToken) withoutRedirect);
  }
}
