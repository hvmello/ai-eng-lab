package io.github.hvmello.aienglab.mcpserver;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the MCP server. Only bound when {@code aienglab.mcp.enabled=true}, so the checks in
 * the constructor make startup fail when credentials are missing.
 */
@ConfigurationProperties(prefix = "aienglab.mcp")
public record McpServerProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("http://127.0.0.1:8080") String baseUrl,
    @DefaultValue("") String user,
    @DefaultValue("") String userPasswordHash,
    @DefaultValue("") String cursorKey,
    @DefaultValue("4000") int pageChars,
    @DefaultValue("20") int defaultLimit,
    @DefaultValue("50") int maxLimit,
    @DefaultValue("5m") Duration accessTokenTtl,
    @DefaultValue("8h") Duration refreshTokenTtl,
    @DefaultValue("10") int registrationsPerIpPerHour,
    @DefaultValue("200") int maxRegisteredClients,
    @DefaultValue List<String> allowedOrigins) {

  public static final String SCOPE = "docs.read";

  private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$\\d\\d\\$[./A-Za-z0-9]{53}$");

  public McpServerProperties {
    baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (enabled) {
      require(
          !user.isBlank(), "MCP_USER (aienglab.mcp.user) must be set when the server is enabled");
      require(
          BCRYPT.matcher(userPasswordHash).matches(),
          "MCP_USER_PASSWORD_HASH (aienglab.mcp.user-password-hash) must be a bcrypt hash");
      require(
          cursorKey.length() >= 32,
          "MCP_CURSOR_KEY (aienglab.mcp.cursor-key) must have at least 32 characters");
      URI uri = URI.create(baseUrl);
      require(
          uri.getScheme() != null && uri.getHost() != null,
          "aienglab.mcp.base-url must be an absolute URL");
    }
    require(pageChars > 0 && defaultLimit > 0 && maxLimit >= defaultLimit, "invalid paging limits");
  }

  /** The protected resource identifier (RFC 8707 / RFC 9728): the MCP endpoint. */
  public String resource() {
    return baseUrl + "/mcp";
  }

  public String resourceMetadataUrl() {
    return baseUrl + "/.well-known/oauth-protected-resource/mcp";
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalStateException(message);
    }
  }
}
