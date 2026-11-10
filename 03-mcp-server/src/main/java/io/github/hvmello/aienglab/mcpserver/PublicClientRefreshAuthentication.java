package io.github.hvmello.aienglab.mcpserver;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Client authentication for public clients on the two endpoints where Spring Authorization Server
 * has no PKCE parameter to authenticate with: the refresh token grant and token revocation.
 *
 * <p>A public client has no secret, so it is identified by {@code client_id}. That is safe here
 * because the bearer token in the same request is what proves anything, and Spring checks that the
 * token was issued to this client.
 */
public final class PublicClientRefreshAuthentication {

  private PublicClientRefreshAuthentication() {}

  public static final class Converter implements AuthenticationConverter {
    @Override
    public Authentication convert(HttpServletRequest request) {
      if (!"POST".equals(request.getMethod())) {
        return null;
      }
      String path = request.getRequestURI();
      boolean refresh =
          "/oauth2/token".equals(path) && "refresh_token".equals(request.getParameter("grant_type"));
      boolean revoke = "/oauth2/revoke".equals(path);
      if (!refresh && !revoke) {
        return null;
      }
      if (request.getHeader("Authorization") != null
          || request.getParameter("client_secret") != null
          || request.getParameter("code_verifier") != null) {
        return null;
      }
      String[] clientIds = request.getParameterValues("client_id");
      if (clientIds == null || clientIds.length != 1 || clientIds[0].isBlank()) {
        return null;
      }
      Map<String, Object> additional = new HashMap<>();
      request
          .getParameterMap()
          .forEach(
              (k, v) -> {
                if (!"client_id".equals(k)) {
                  additional.put(k, v.length == 1 ? v[0] : v);
                }
              });
      return new OAuth2ClientAuthenticationToken(
          clientIds[0], ClientAuthenticationMethod.NONE, null, additional);
    }
  }

  public static final class Provider implements AuthenticationProvider {
    private final RegisteredClientRepository clients;

    public Provider(RegisteredClientRepository clients) {
      this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication)
        throws AuthenticationException {
      OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
      if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
          || token.getAdditionalParameters().containsKey("code_verifier")) {
        return null; // the PKCE provider handles those
      }
      RegisteredClient client = clients.findByClientId(token.getPrincipal().toString());
      if (client == null
          || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, "Client authentication failed", null));
      }
      return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
      return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
  }
}
