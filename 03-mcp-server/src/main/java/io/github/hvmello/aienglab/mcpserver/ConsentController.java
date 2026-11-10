package io.github.hvmello.aienglab.mcpserver;

import java.net.URI;
import java.util.Arrays;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * The consent screen: shows who is asking and for what, and posts back to the authorize endpoint.
 *
 * <p>Registration is anonymous, so the client name is only what the client called itself. The
 * screen says so and shows the host the authorization code will be sent to, taken from the pending
 * authorization request, which is the part an attacker cannot disguise.
 */
@Controller
public class ConsentController {

  private final RegisteredClientRepository clients;
  private final OAuth2AuthorizationService authorizations;

  public ConsentController(
      RegisteredClientRepository clients, OAuth2AuthorizationService authorizations) {
    this.clients = clients;
    this.authorizations = authorizations;
  }

  @GetMapping(path = "/oauth2/consent", produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public String consent(
      @RequestParam("client_id") String clientId,
      @RequestParam(name = "scope", required = false) String scope,
      @RequestParam("state") String state,
      CsrfToken csrf) {
    RegisteredClient client = clients.findByClientId(clientId);
    String name = client == null ? "Unknown client" : client.getClientName();
    String destination = destination(state);
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        .append("<title>Authorize access</title></head><body>")
        .append("<h1>Authorize access</h1><p><strong>")
        .append(HtmlUtils.htmlEscape(name))
        .append("</strong> wants to read your documents.</p>")
        .append("<p>The name above was chosen by the application itself and is <strong>not")
        .append(" verified</strong>.</p>")
        .append("<p>If you allow, you will be sent back to <strong>")
        .append(HtmlUtils.htmlEscape(destination))
        .append("</strong>. Allow only if you recognise that address.</p>")
        .append("<form method=\"post\" action=\"/oauth2/authorize\">");
    hidden(html, "client_id", clientId);
    hidden(html, "state", state);
    hidden(html, csrf.getParameterName(), csrf.getToken());
    if (scope != null) {
      Arrays.stream(scope.trim().split("\\s+"))
          .filter(s -> !s.isEmpty())
          .forEach(
              s ->
                  html.append("<p><label><input type=\"checkbox\" name=\"scope\" value=\"")
                      .append(HtmlUtils.htmlEscape(s))
                      .append("\" checked> ")
                      .append(HtmlUtils.htmlEscape(s))
                      .append(" (read-only access to documents)</label></p>"));
    }
    html.append("<button type=\"submit\">Allow</button></form>")
        .append("<form method=\"post\" action=\"/oauth2/authorize\">");
    hidden(html, "client_id", clientId);
    hidden(html, "state", state);
    hidden(html, csrf.getParameterName(), csrf.getToken());
    html.append("<button type=\"submit\">Deny</button></form></body></html>");
    return html.toString();
  }

  /** The redirect URI of the pending authorization request: scheme, host and port, no query. */
  private String destination(String state) {
    OAuth2Authorization pending =
        authorizations.findByToken(state, new OAuth2TokenType(OAuth2ParameterNames.STATE));
    if (pending == null) {
      return "an unknown address";
    }
    OAuth2AuthorizationRequest request =
        pending.getAttribute(OAuth2AuthorizationRequest.class.getName());
    if (request == null || request.getRedirectUri() == null) {
      return "an unknown address";
    }
    try {
      URI uri = URI.create(request.getRedirectUri());
      if (uri.getHost() == null) {
        return "an unknown address";
      }
      return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    } catch (IllegalArgumentException e) {
      return "an unknown address";
    }
  }

  private static void hidden(StringBuilder html, String name, String value) {
    html.append("<input type=\"hidden\" name=\"")
        .append(HtmlUtils.htmlEscape(name))
        .append("\" value=\"")
        .append(HtmlUtils.htmlEscape(value))
        .append("\">");
  }
}
