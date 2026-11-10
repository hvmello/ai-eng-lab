package io.github.hvmello.aienglab.mcpserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RFC 8707: the {@code resource} parameter, when present on the authorization or token request,
 * must be the canonical MCP endpoint URL. There is only one resource on this server, so any other
 * value is {@code invalid_target}.
 */
public class ResourceParameterFilter extends OncePerRequestFilter {

  private final String canonicalResource;

  public ResourceParameterFilter(String canonicalResource) {
    this.canonicalResource = canonicalResource;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !("/oauth2/authorize".equals(path) || "/oauth2/token".equals(path));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String[] resources = request.getParameterValues("resource");
    if (resources != null) {
      for (String resource : resources) {
        if (!canonicalResource.equals(resource)) {
          response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
          response.setContentType("application/json");
          response.setHeader("Cache-Control", "no-store");
          response
              .getOutputStream()
              .write(
                  "{\"error\":\"invalid_target\",\"error_description\":\"resource must be the MCP endpoint\"}"
                      .getBytes(StandardCharsets.UTF_8));
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }
}
