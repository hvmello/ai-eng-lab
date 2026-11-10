package io.github.hvmello.aienglab.mcpserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Serialises refresh requests that carry the same refresh token.
 *
 * <p>The JDBC authorization service has no compare-and-set: two parallel refreshes would both read
 * the old token, both rotate it, and both succeed. This filter runs the whole token request in one
 * transaction that first locks the authorization row ({@code SELECT ... FOR UPDATE}). The second
 * request waits, then finds the token gone (it is now retired) and fails with {@code invalid_grant}.
 *
 * <p>The response body is held back until the transaction has committed, so a client can never
 * receive a new refresh token that is not yet visible in the database.
 */
public class RefreshTokenLockFilter extends OncePerRequestFilter {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public RefreshTokenLockFilter(JdbcTemplate jdbc, TransactionTemplate transaction) {
    this.jdbc = jdbc;
    this.transaction = transaction;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !"/oauth2/token".equals(request.getRequestURI())
        || !"refresh_token".equals(request.getParameter("grant_type"))
        || request.getParameter("refresh_token") == null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String refreshToken = request.getParameter("refresh_token");
    ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
    Exception[] failure = new Exception[1];
    transaction.executeWithoutResult(
        status -> {
          jdbc.queryForList(
              "SELECT id FROM oauth2_authorization WHERE refresh_token_value = ? FOR UPDATE",
              String.class,
              refreshToken);
          try {
            chain.doFilter(request, buffered);
          } catch (IOException | ServletException | RuntimeException e) {
            failure[0] = e;
            status.setRollbackOnly();
          }
        });
    if (failure[0] != null) {
      if (failure[0] instanceof IOException io) {
        throw io;
      }
      if (failure[0] instanceof ServletException se) {
        throw se;
      }
      throw (RuntimeException) failure[0];
    }
    buffered.copyBodyToResponse();
  }
}
