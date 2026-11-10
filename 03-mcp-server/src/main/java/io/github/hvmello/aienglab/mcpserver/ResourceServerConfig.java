package io.github.hvmello.aienglab.mcpserver;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * The protected resource: {@code /mcp} behind bearer tokens, the RFC 9728 metadata documents, and
 * the three document tools on Spring AI's stateless Streamable HTTP transport.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aienglab.mcp", name = "enabled", havingValue = "true")
public class ResourceServerConfig {

  private static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

  @Bean
  AccessTokenValidator accessTokenValidator(OAuth2AuthorizationService authorizations, Clock clock) {
    return new AccessTokenValidator(authorizations, clock);
  }

  @Bean
  DocumentRepository documentRepository(JdbcTemplate jdbc) {
    return new DocumentRepository(jdbc);
  }

  @Bean
  DocumentTools documentTools(
      DocumentRepository documents, AccessTokenValidator validator, McpServerProperties properties) {
    return new DocumentTools(documents, validator, properties);
  }

  /**
   * The MCP transport. Replaces Spring AI's default so that the bearer token reaches the tool
   * (which re-checks it), and so that Origin and Host are validated against DNS rebinding.
   */
  @Bean
  WebMvcStatelessServerTransport webMvcStatelessServerTransport(
      @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerProperties properties) {
    McpTransportContextExtractor<ServerRequest> extractor =
        request -> {
          String header = request.headers().firstHeader("Authorization");
          if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return McpTransportContext.create(
                Map.of(DocumentTools.CONTEXT_TOKEN_KEY, header.substring(7).trim()));
          }
          return McpTransportContext.EMPTY;
        };
    return WebMvcStatelessServerTransport.builder()
        .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
        .messageEndpoint("/mcp")
        .contextExtractor(extractor)
        .securityValidator(securityValidator(properties))
        .build();
  }

  static DefaultServerTransportSecurityValidator securityValidator(McpServerProperties properties) {
    URI base = URI.create(properties.baseUrl());
    String origin = base.getScheme() + "://" + base.getAuthority();
    List<String> origins = new ArrayList<>(List.of(origin));
    List<String> hosts = new ArrayList<>(List.of(base.getAuthority()));
    for (String loopback : List.of("127.0.0.1", "localhost", "[::1]")) {
      origins.add("http://" + loopback + ":*");
      hosts.add(loopback + ":*");
    }
    origins.addAll(properties.allowedOrigins());
    return DefaultServerTransportSecurityValidator.builder()
        .allowedOrigins(origins)
        .allowedHosts(hosts)
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain resourceServerFilterChain(
      HttpSecurity http, AccessTokenValidator validator, McpServerProperties properties)
      throws Exception {
    BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
    entryPoint.setResourceMetadataParameterResolver(request -> properties.resourceMetadataUrl());
    http.securityMatcher("/mcp", "/mcp/**", METADATA_PATH, METADATA_PATH + "/**")
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(METADATA_PATH, METADATA_PATH + "/**")
                    .permitAll()
                    .anyRequest()
                    .hasAuthority("SCOPE_" + McpServerProperties.SCOPE))
        .oauth2ResourceServer(
            resource ->
                resource
                    .opaqueToken(opaque -> opaque.introspector(validator))
                    .authenticationEntryPoint(entryPoint)
                    .protectedResourceMetadata(
                        metadata ->
                            metadata.protectedResourceMetadataCustomizer(
                                builder ->
                                    builder
                                        .resource(properties.resource())
                                        .authorizationServer(properties.baseUrl())
                                        .scope(McpServerProperties.SCOPE)
                                        .resourceName("Documents MCP server")
                                        .tlsClientCertificateBoundAccessTokens(false))))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable);
    return http.build();
  }
}
