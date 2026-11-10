package io.github.hvmello.aienglab.mcpserver;

import java.util.Arrays;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/** The consent screen: shows who is asking and for what, and posts back to the authorize endpoint. */
@Controller
public class ConsentController {

  private final RegisteredClientRepository clients;

  public ConsentController(RegisteredClientRepository clients) {
    this.clients = clients;
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
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        .append("<title>Authorize access</title></head><body>")
        .append("<h1>Authorize access</h1><p><strong>")
        .append(HtmlUtils.htmlEscape(name))
        .append("</strong> wants to read your documents.</p>")
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

  private static void hidden(StringBuilder html, String name, String value) {
    html.append("<input type=\"hidden\" name=\"")
        .append(HtmlUtils.htmlEscape(name))
        .append("\" value=\"")
        .append(HtmlUtils.htmlEscape(value))
        .append("\">");
  }
}
