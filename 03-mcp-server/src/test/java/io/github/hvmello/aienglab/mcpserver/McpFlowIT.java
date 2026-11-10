package io.github.hvmello.aienglab.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hvmello.aienglab.mcpserver.OAuthTestClient.Pkce;
import io.github.hvmello.aienglab.mcpserver.OAuthTestClient.Reply;
import io.github.hvmello.aienglab.mcpserver.OAuthTestClient.Session;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The happy path, PKCE, code replay, scope, expiry and discovery. */
class McpFlowIT extends McpIT {

  private static final String REDIRECT = "http://localhost:45555/callback";

  // 1. End to end ---------------------------------------------------------------------------

  @Test
  void theConsentScreenShowsWhereTheCodeWillBeSentAndThatTheNameIsUnverified() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient("https://attacker.example/cb");

    String page =
        client.consentPage(
            client.authorizeUrl(
                clientId,
                "https://attacker.example/cb",
                OAuthTestClient.Pkce.create(),
                "docs.read"));

    assertThat(page).contains("<strong>https://attacker.example</strong>");
    assertThat(page).contains("<strong>not verified</strong>");
  }

  @Test
  void registerLoginAuthorizeExchangeThenUseEveryTool() {
    OAuthTestClient client = browser();

    Session session = client.signIn(REDIRECT);
    String token = session.tokens().accessToken();

    assertThat(session.tokens().scope()).isEqualTo("docs.read");
    assertThat(session.tokens().refreshToken()).isNotBlank();

    Reply init =
        client.mcp(
            token,
            "initialize",
            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}");
    assertThat(init.status()).isEqualTo(200);
    assertThat(init.json().get("result").get("serverInfo").get("name").asString())
        .isEqualTo("docs-mcp-server");

    Reply tools = client.mcp(token, "tools/list", "{}");
    assertThat(OAuthTestClient.toolNames(tools))
        .containsExactlyInAnyOrder("list_documents", "search_documents", "read_document");

    JsonNode list = OAuthTestClient.toolPayload(client.callTool(token, "list_documents", "{\"limit\":3}"));
    assertThat(list.get("documents")).hasSize(3);
    assertThat(list.get("next_cursor").asString()).isNotBlank();
    JsonNode page2 =
        OAuthTestClient.toolPayload(
            client.callTool(
                token,
                "list_documents",
                "{\"limit\":3,\"cursor\":\"" + list.get("next_cursor").asString() + "\"}"));
    assertThat(page2.get("documents").get(0).get("id").asLong())
        .isGreaterThan(list.get("documents").get(2).get("id").asLong());

    JsonNode found =
        OAuthTestClient.toolPayload(
            client.callTool(token, "search_documents", "{\"query\":\"lighthouse keeper\"}"));
    assertThat(found.get("documents").get(0).get("title").asString()).contains("lighthouse");

    long id = found.get("documents").get(0).get("id").asLong();
    JsonNode read =
        OAuthTestClient.toolPayload(client.callTool(token, "read_document", "{\"id\":" + id + "}"));
    assertThat(read.get("text").asString()).startsWith("The lamp had burned");
    assertThat(read.get("text").asString()).hasSize(120);
    assertThat(read.get("next_cursor").asString()).isNotBlank();
  }

  @Test
  void aPaginatedReadReassemblesTheWholeDocument() {
    OAuthTestClient client = browser();
    String token = client.signIn(REDIRECT).tokens().accessToken();
    long id = firstDocumentId(client, token, "lighthouse");
    String expected =
        jdbc.queryForObject("SELECT body FROM documents WHERE id = ?", String.class, id);

    StringBuilder text = new StringBuilder();
    String cursor = null;
    int pages = 0;
    do {
      String args =
          "{\"id\":" + id + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
      JsonNode page = OAuthTestClient.toolPayload(client.callTool(token, "read_document", args));
      assertThat(page.get("text").asString().length()).isLessThanOrEqualTo(120);
      text.append(page.get("text").asString());
      cursor = page.has("next_cursor") ? page.get("next_cursor").asString() : null;
      pages++;
    } while (cursor != null && pages < 50);

    assertThat(text.toString()).isEqualTo(expected);
    assertThat(pages).isEqualTo((expected.length() + 119) / 120);
  }

  @Test
  void aPaginatedReadKeepsEveryCharacterOutsideTheBasicMultilingualPlane() {
    // Supplementary characters are 2 UTF-16 units in Java but 1 character in PostgreSQL. The body
    // puts them across the 120 character page boundary.
    String body = ("ab😀cdefg🚀").repeat(30);
    long id =
        jdbc.queryForObject(
            "INSERT INTO documents (title, body) VALUES ('emoji paging probe', ?) RETURNING id",
            Long.class,
            body);
    try {
      OAuthTestClient client = browser();
      String token = client.signIn(REDIRECT).tokens().accessToken();

      StringBuilder text = new StringBuilder();
      String cursor = null;
      int pages = 0;
      do {
        String args =
            "{\"id\":" + id + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
        JsonNode page = OAuthTestClient.toolPayload(client.callTool(token, "read_document", args));
        text.append(page.get("text").asString());
        cursor = page.has("next_cursor") ? page.get("next_cursor").asString() : null;
        pages++;
      } while (cursor != null && pages < 50);

      assertThat(text.toString()).isEqualTo(body);
      assertThat(pages).isEqualTo((body.codePointCount(0, body.length()) + 119) / 120);
    } finally {
      jdbc.update("DELETE FROM documents WHERE id = ?", id);
    }
  }

  @Test
  void aTamperedOrForeignCursorIsAToolError() {
    OAuthTestClient client = browser();
    String token = client.signIn(REDIRECT).tokens().accessToken();
    JsonNode list = OAuthTestClient.toolPayload(client.callTool(token, "list_documents", "{\"limit\":1}"));
    String cursor = list.get("next_cursor").asString();

    Reply tampered =
        client.callTool(token, "list_documents", "{\"cursor\":\"" + cursor + "x\"}");
    Reply foreign =
        client.callTool(token, "search_documents", "{\"query\":\"mcp\",\"cursor\":\"" + cursor + "\"}");

    for (Reply reply : List.of(tampered, foreign)) {
      assertThat(OAuthTestClient.toolFailed(reply)).isTrue();
      assertThat(OAuthTestClient.toolPayload(reply).get("error").asString()).isEqualTo("invalid_cursor");
    }
  }

  @Test
  void searchTakesHostileInputAsDataOnly() {
    OAuthTestClient client = browser();
    String token = client.signIn(REDIRECT).tokens().accessToken();

    Reply reply =
        client.callTool(
            token, "search_documents", "{\"query\":\"'; DROP TABLE documents; --\"}");

    assertThat(OAuthTestClient.toolFailed(reply)).isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class)).isGreaterThan(0);
  }

  // 2. PKCE ---------------------------------------------------------------------------------

  @Test
  void authorizationWithoutPkceIsRejected() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient(REDIRECT);

    String location =
        client.authorizeAndConsent(client.authorizeUrl(clientId, REDIRECT, null, "docs.read"));

    assertThat(client.query(location)).containsEntry("error", "invalid_request");
    assertThat(client.query(location)).doesNotContainKey("code");
  }

  @Test
  void thePlainChallengeMethodIsRejected() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient(REDIRECT);
    Pkce pkce = Pkce.create();

    String location =
        client.authorizeAndConsent(
            client.authorizeUrl(
                clientId, REDIRECT, pkce, "docs.read", "code_challenge_method", "plain"));

    assertThat(client.query(location)).containsEntry("error", "invalid_request");
  }

  @Test
  void aWrongVerifierIsRejectedAndIssuesNothing() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient(REDIRECT);
    Pkce pkce = Pkce.create();
    String code = client.obtainCode(clientId, REDIRECT, pkce);

    Reply wrong = client.exchangeCode(clientId, REDIRECT, code, Pkce.create().verifier());

    assertThat(wrong.status()).isEqualTo(400);
    assertThat(wrong.json().get("error").asString()).isEqualTo("invalid_grant");
    assertThat(wrong.body()).doesNotContain("access_token");
  }

  @Test
  void aCodeExchangeWithoutAVerifierIsRejected() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient(REDIRECT);
    String code = client.obtainCode(clientId, REDIRECT, Pkce.create());

    Reply reply =
        client.postForm(
            "/oauth2/token",
            Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT,
                "client_id", clientId));

    assertThat(reply.status()).isGreaterThanOrEqualTo(400);
    assertThat(reply.body()).doesNotContain("access_token");
  }

  // 3. Code replay --------------------------------------------------------------------------

  @Test
  void replayingACodeFailsAndRevokesWhatTheFirstExchangeIssued() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient(REDIRECT);
    Pkce pkce = Pkce.create();
    String code = client.obtainCode(clientId, REDIRECT, pkce);

    Reply first = client.exchangeCode(clientId, REDIRECT, code, pkce.verifier());
    assertThat(first.status()).isEqualTo(200);
    String accessToken = OAuthTestClient.tokens(first).accessToken();
    assertThat(client.callTool(accessToken, "list_documents", "{}").status()).isEqualTo(200);

    Reply replay = client.exchangeCode(clientId, REDIRECT, code, pkce.verifier());

    assertThat(replay.status()).isEqualTo(400);
    assertThat(replay.json().get("error").asString()).isEqualTo("invalid_grant");
    assertThat(client.callTool(accessToken, "list_documents", "{}").status()).isEqualTo(401);
  }

  // 7. Expiry -------------------------------------------------------------------------------

  @Test
  void anAccessTokenExpiresAfterItsShortLifetime() {
    OAuthTestClient client = browser();
    String token = client.signIn(REDIRECT).tokens().accessToken();
    assertThat(client.callTool(token, "list_documents", "{}").status()).isEqualTo(200);

    clock.advance(Duration.ofMinutes(4).plusSeconds(50));
    assertThat(client.callTool(token, "list_documents", "{}").status()).isEqualTo(200);

    clock.advance(Duration.ofSeconds(20));
    Reply expired = client.callTool(token, "list_documents", "{}");
    assertThat(expired.status()).isEqualTo(401);
    assertThat(expired.header("WWW-Authenticate")).contains("Bearer");
  }

  // 8. Scope --------------------------------------------------------------------------------

  @Test
  void registeringAWiderScopeFails() {
    Reply reply = browser().register(REDIRECT, "docs.read admin");

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.json().get("error").asString()).isEqualTo("invalid_client_metadata");
  }

  @Test
  void anAuthorizeRequestWithAnExtraScopeIsInvalidScope() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient(REDIRECT);

    String location =
        client.authorizeAndConsent(
            client.authorizeUrl(clientId, REDIRECT, Pkce.create(), "docs.read admin"));

    assertThat(client.query(location)).containsEntry("error", "invalid_scope");
  }

  @Test
  void aRefreshAskingForAWiderScopeIsInvalidScope() {
    OAuthTestClient client = browser();
    Session session = client.signIn(REDIRECT);

    Reply reply =
        client.refresh(
            session.clientId(), session.tokens().refreshToken(), "scope", "docs.read admin");

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.json().get("error").asString()).isEqualTo("invalid_scope");
  }

  @Test
  void theServerExposesExactlyThreeToolsAndNothingElse() {
    OAuthTestClient client = browser();
    String token = client.signIn(REDIRECT).tokens().accessToken();

    assertThat(OAuthTestClient.toolNames(client.mcp(token, "tools/list", "{}")))
        .containsExactlyInAnyOrder("list_documents", "search_documents", "read_document");

    for (String method : List.of("resources/list", "prompts/list")) {
      JsonNode body = client.mcp(token, method, "{}").json();
      boolean refused = body.has("error");
      boolean empty =
          body.has("result")
              && (body.get("result").path("resources").isEmpty()
                  || body.get("result").path("prompts").isEmpty());
      assertThat(refused || empty).as(method).isTrue();
    }

    Reply admin = client.callTool(token, "delete_document", "{\"id\":1}");
    assertThat(admin.json().has("error") || OAuthTestClient.toolFailed(admin)).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class)).isEqualTo(8);
  }

  @Test
  void theToolDescriptionsNeverContainDocumentText() {
    OAuthTestClient client = browser();
    String token = client.signIn(REDIRECT).tokens().accessToken();

    JsonNode tools = client.mcp(token, "tools/list", "{}").json().get("result").get("tools");
    List<String> bodies = jdbc.queryForList("SELECT body FROM documents", String.class);

    for (JsonNode tool : tools) {
      String description = tool.get("description").asString();
      for (String body : bodies) {
        assertThat(description).doesNotContain(body.substring(0, 40));
      }
    }
  }

  // 11. Discovery, 401 and Origin -----------------------------------------------------------

  @Test
  void anUnauthenticatedRequestGets401WithTheMetadataPointer() {
    HttpClient anonymous = OAuthTestClient.anonymous();

    Reply reply =
        OAuthTestClient.send(
            anonymous,
            HttpRequest.newBuilder(java.net.URI.create(BASE + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());

    assertThat(reply.status()).isEqualTo(401);
    assertThat(reply.header("WWW-Authenticate"))
        .startsWith("Bearer")
        .contains("resource_metadata=\"" + BASE + "/.well-known/oauth-protected-resource/mcp\"");
  }

  @Test
  void aGarbageTokenGets401() {
    Reply reply = browser().callTool("not-a-real-token", "list_documents", "{}");

    assertThat(reply.status()).isEqualTo(401);
    assertThat(reply.header("WWW-Authenticate")).contains("resource_metadata=");
  }

  @Test
  void theProtectedResourceMetadataDocumentsTheServer() {
    for (String path :
        List.of("/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource")) {
      Reply reply = browser().get(path);

      assertThat(reply.status()).as(path).isEqualTo(200);
      JsonNode json = reply.json();
      assertThat(json.get("resource").asString()).isEqualTo(BASE + "/mcp");
      assertThat(json.get("authorization_servers").get(0).asString()).isEqualTo(BASE);
      assertThat(json.get("scopes_supported").get(0).asString()).isEqualTo("docs.read");
      assertThat(json.get("bearer_methods_supported").get(0).asString()).isEqualTo("header");
    }
  }

  @Test
  void theAuthorizationServerMetadataAdvertisesWhatClientsNeed() {
    Reply reply = browser().get("/.well-known/oauth-authorization-server");

    assertThat(reply.status()).isEqualTo(200);
    JsonNode json = reply.json();
    assertThat(json.get("issuer").asString()).isEqualTo(BASE);
    assertThat(json.get("authorization_endpoint").asString()).isEqualTo(BASE + "/oauth2/authorize");
    assertThat(json.get("token_endpoint").asString()).isEqualTo(BASE + "/oauth2/token");
    assertThat(json.get("revocation_endpoint").asString()).isEqualTo(BASE + "/oauth2/revoke");
    assertThat(json.get("registration_endpoint").asString()).isEqualTo(BASE + "/register");
    assertThat(texts(json.get("code_challenge_methods_supported"))).containsExactly("S256");
    assertThat(texts(json.get("grant_types_supported")))
        .contains("authorization_code", "refresh_token")
        .doesNotContain("client_credentials", "password");
    assertThat(texts(json.get("token_endpoint_auth_methods_supported"))).contains("none");
    assertThat(texts(json.get("scopes_supported"))).contains("docs.read");
  }

  @Test
  void aBadOriginIsForbidden() {
    OAuthTestClient client = browser();
    String token = client.signIn(REDIRECT).tokens().accessToken();

    Reply evil =
        client.mcp(token, "tools/list", "{}", "Origin", "http://evil.example");
    Reply local =
        client.mcp(token, "tools/list", "{}", "Origin", "http://localhost:3000");

    assertThat(evil.status()).isEqualTo(403);
    assertThat(local.status()).isEqualTo(200);
  }

  @Test
  void aResourceParameterMustBeTheMcpEndpoint() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient(REDIRECT);

    Reply bad =
        client.get(
            client.authorizeUrl(
                clientId, REDIRECT, Pkce.create(), "docs.read", "resource", "https://other.example/mcp"));
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.json().get("error").asString()).isEqualTo("invalid_target");

    Pkce pkce = Pkce.create();
    String location =
        client.authorizeAndConsent(
            client.authorizeUrl(clientId, REDIRECT, pkce, "docs.read", "resource", BASE + "/mcp"));
    String code = client.query(location).get("code");
    Reply wrongAtToken =
        client.exchangeCode(
            clientId, REDIRECT, code, pkce.verifier(), "resource", "https://other.example/mcp");
    assertThat(wrongAtToken.status()).isEqualTo(400);
    assertThat(wrongAtToken.json().get("error").asString()).isEqualTo("invalid_target");
  }

  @Test
  void localhostRedirectsMayUseAnyPortButNotAnyHost() {
    OAuthTestClient client = browser();
    String clientId = client.registerClient(REDIRECT);

    String otherPort = "http://localhost:51234/callback";
    String location =
        client.authorizeAndConsent(
            client.authorizeUrl(clientId, otherPort, Pkce.create(), "docs.read"));
    assertThat(location).startsWith(otherPort + "?code=");

    Reply otherHost =
        client.get(client.authorizeUrl(clientId, "http://evil.example/callback", Pkce.create(), "docs.read"));
    assertThat(otherHost.status()).isNotEqualTo(302);
    Reply otherPath =
        client.get(client.authorizeUrl(clientId, "http://localhost:51234/other", Pkce.create(), "docs.read"));
    assertThat(otherPath.header("Location")).isNull();
  }

  // ----------------------------------------------------------------------------------------

  static long firstDocumentId(OAuthTestClient client, String token, String query) {
    JsonNode found =
        OAuthTestClient.toolPayload(
            client.callTool(token, "search_documents", "{\"query\":\"" + query + "\"}"));
    return found.get("documents").get(0).get("id").asLong();
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }
}
