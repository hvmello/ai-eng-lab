package io.github.hvmello.aienglab.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hvmello.aienglab.mcpserver.DocumentPage.Page;
import io.github.hvmello.aienglab.mcpserver.DocumentPage.Slice;
import io.github.hvmello.aienglab.mcpserver.DocumentPage.Summary;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tool behaviour that needs no database: limits, typed errors and the per-call authorization. */
class DocumentToolsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final InMemoryOAuth2AuthorizationService authorizations =
      new InMemoryOAuth2AuthorizationService();
  private final RecordingRepository repository = new RecordingRepository();
  private DocumentTools tools;

  @BeforeEach
  void setUp() {
    McpServerProperties properties = properties();
    tools =
        new DocumentTools(repository, new AccessTokenValidator(authorizations, clock), properties);
  }

  private static McpServerProperties properties() {
    return new McpServerProperties(
        true,
        "http://127.0.0.1:8080",
        "someone",
        "$2a$04$" + "a".repeat(53),
        "c".repeat(40),
        100,
        20,
        50,
        Duration.ofMinutes(5),
        Duration.ofHours(8),
        10,
        200,
        List.of());
  }

  private McpTransportContext authorized(String... scopes) {
    RegisteredClient client =
        RegisteredClient.withId("rc-1")
            .clientId("client-1")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://127.0.0.1/cb")
            .build();
    OAuth2Authorization authorization =
        OAuth2Authorization.withRegisteredClient(client)
            .id("a-1")
            .principalName("someone")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of(scopes))
            .token(
                new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    "good-token",
                    NOW.minusSeconds(10),
                    NOW.plusSeconds(290),
                    Set.of(scopes)))
            .build();
    authorizations.save(authorization);
    return McpTransportContext.create(Map.of(DocumentTools.CONTEXT_TOKEN_KEY, "good-token"));
  }

  @Test
  void capsTheLimitOnTheServer() {
    McpTransportContext context = authorized("docs.read");

    tools.listDocuments(context, null, 100_000);
    tools.listDocuments(context, null, 0);
    tools.listDocuments(context, null, -5);
    tools.listDocuments(context, null, null);

    assertThat(repository.limits).containsExactly(50, 1, 1, 20);
  }

  @Test
  void capsTheLimitOfSearchToo() {
    McpTransportContext context = authorized("docs.read");

    tools.searchDocuments(context, "lighthouse", null, 9999);

    assertThat(repository.limits).containsExactly(50);
  }

  @Test
  void aTamperedCursorIsATypedToolError() {
    McpTransportContext context = authorized("docs.read");

    CallToolResult result = tools.listDocuments(context, "AAAA.BBBB", 5);

    assertThat(result.isError()).isTrue();
    assertThat(body(result).get("error").asString()).isEqualTo("invalid_cursor");
    assertThat(repository.limits).isEmpty();
  }

  @Test
  void aCursorFromAnotherToolIsRejected() {
    McpTransportContext context = authorized("docs.read");
    String listCursor = new Cursor(Cursor.LIST, "", 3).encode(properties().cursorKey().getBytes(StandardCharsets.UTF_8));

    CallToolResult result = tools.searchDocuments(context, "anything", listCursor, 5);

    assertThat(result.isError()).isTrue();
    assertThat(body(result).get("error").asString()).isEqualTo("invalid_cursor");
  }

  @Test
  void aSearchCursorIsBoundToItsQuery() {
    McpTransportContext context = authorized("docs.read");
    repository.hasMore = true;
    CallToolResult first = tools.searchDocuments(context, "lighthouse", null, 1);
    String next = body(first).get("next_cursor").asString();

    CallToolResult reused = tools.searchDocuments(context, "something else", next, 1);

    assertThat(reused.isError()).isTrue();
    assertThat(body(reused).get("error").asString()).isEqualTo("invalid_cursor");
  }

  @Test
  void readReturnsNextCursorOnlyWhileTheDocumentContinues() {
    McpTransportContext context = authorized("docs.read");
    repository.slice = new Slice(1, "t", NOW, 250, 0, "x".repeat(100));

    JsonNode first = body(tools.readDocument(context, 1L, null));
    assertThat(first.has("next_cursor")).isTrue();

    // The last page ends exactly at the end of the body: 200 + 50 == 250.
    repository.slice = new Slice(1, "t", NOW, 250, 200, "x".repeat(50));
    JsonNode last = body(tools.readDocument(context, 1L, null));
    assertThat(last.has("next_cursor")).isFalse();

    // A body that is an exact multiple of the page size has no empty trailing page.
    repository.slice = new Slice(1, "t", NOW, 200, 100, "x".repeat(100));
    JsonNode exact = body(tools.readDocument(context, 1L, null));
    assertThat(exact.has("next_cursor")).isFalse();
  }

  @Test
  void missingDocumentIsATypedError() {
    McpTransportContext context = authorized("docs.read");
    repository.slice = null;

    CallToolResult result = tools.readDocument(context, 99L, null);

    assertThat(result.isError()).isTrue();
    assertThat(body(result).get("error").asString()).isEqualTo("not_found");
  }

  @Test
  void everyCallNeedsAValidToken() {
    CallToolResult noToken = tools.listDocuments(McpTransportContext.EMPTY, null, null);
    CallToolResult unknown =
        tools.listDocuments(
            McpTransportContext.create(Map.of(DocumentTools.CONTEXT_TOKEN_KEY, "made-up")), null, null);

    for (CallToolResult result : List.of(noToken, unknown)) {
      assertThat(result.isError()).isTrue();
      assertThat(body(result).get("error").asString()).isEqualTo("unauthorized");
    }
    assertThat(repository.limits).isEmpty();
  }

  @Test
  void aRevokedTokenIsRefusedOnTheNextCall() {
    McpTransportContext context = authorized("docs.read");
    assertThat(tools.listDocuments(context, null, null).isError()).isFalse();

    OAuth2Authorization stored = authorizations.findById("a-1");
    authorizations.save(
        OAuth2Authorization.from(stored).invalidate(stored.getAccessToken().getToken()).build());

    CallToolResult result = tools.listDocuments(context, null, null);
    assertThat(result.isError()).isTrue();
    assertThat(body(result).get("error").asString()).isEqualTo("unauthorized");
  }

  @Test
  void anExpiredTokenIsRefused() {
    McpTransportContext context = authorized("docs.read");
    DocumentTools later =
        new DocumentTools(
            repository,
            new AccessTokenValidator(authorizations, Clock.fixed(NOW.plusSeconds(600), ZoneOffset.UTC)),
            properties());

    CallToolResult result = later.listDocuments(context, null, null);

    assertThat(result.isError()).isTrue();
    assertThat(body(result).get("error").asString()).isEqualTo("unauthorized");
  }

  @Test
  void theScopeIsCheckedInTheToolLayer() {
    McpTransportContext context = authorized("something.else");

    CallToolResult result = tools.listDocuments(context, null, null);

    assertThat(result.isError()).isTrue();
    assertThat(body(result).get("error").asString()).isEqualTo("insufficient_scope");
    assertThat(repository.limits).isEmpty();
  }

  @Test
  void descriptionsAreStaticConstants() throws Exception {
    List<String> descriptions = new ArrayList<>();
    for (Method method : DocumentTools.class.getDeclaredMethods()) {
      McpTool annotation = method.getAnnotation(McpTool.class);
      if (annotation != null) {
        descriptions.add(annotation.name());
        assertThat(annotation.description()).isNotBlank();
      }
    }
    assertThat(descriptions)
        .containsExactlyInAnyOrder("list_documents", "search_documents", "read_document");
  }

  private static JsonNode body(CallToolResult result) {
    return JSON.readTree(((TextContent) result.content().get(0)).text());
  }

  /** Stands in for the database and records what the tools asked for. */
  private static final class RecordingRepository extends DocumentRepository {
    final List<Integer> limits = new ArrayList<>();
    boolean hasMore;
    Slice slice = new Slice(1, "t", NOW, 10, 0, "0123456789");

    RecordingRepository() {
      super(null);
    }

    @Override
    public Page<Summary> list(long afterId, int limit) {
      limits.add(limit);
      return new Page<>(List.of(), -1);
    }

    @Override
    public Page<Summary> search(String query, long offset, int limit) {
      limits.add(limit);
      return new Page<>(List.of(new Summary(1, "t", NOW, 5)), hasMore ? offset + limit : -1);
    }

    @Override
    public Optional<Slice> read(long id, long offset, int maxChars) {
      return Optional.ofNullable(slice);
    }
  }
}
