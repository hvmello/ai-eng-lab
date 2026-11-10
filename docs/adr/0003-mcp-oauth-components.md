# 3. OAuth components for the MCP server

**Status:** Accepted

## Decision

Module `03-mcp-server` is one Spring Boot application that is the authorization server, the protected resource and
the MCP server. It is built from Spring components, and only the gaps are custom code:

- **Authorization server:** Spring Security 7's `OAuth2AuthorizationServerConfigurer`, with
  `JdbcRegisteredClientRepository`, `JdbcOAuth2AuthorizationService` and `JdbcOAuth2AuthorizationConsentService` on
  PostgreSQL (the Spring schema ported to `text` and `timestamptz`, in a Flyway migration).
- **Protected resource:** Spring Security 7's resource server with its built-in RFC 9728 metadata filter and
  `BearerTokenAuthenticationEntryPoint`. Access tokens are opaque, checked by an in-process
  `OpaqueTokenIntrospector` that reads the authorization record (one lookup per request).
- **MCP:** Spring AI's MCP server starter for WebMVC, in stateless Streamable HTTP mode, with a custom transport bean
  so the bearer token reaches the tool and the SDK's `Origin` and `Host` validator is on.
- **Dynamic registration:** a small custom `POST /register` controller (RFC 7591) for public clients.

## Why custom registration and not a library

- Spring Authorization Server's registration endpoint (`OidcClientRegistrationEndpoint`, and the OAuth 2.0 variant)
  requires an initial access token with a client-registration scope. An MCP client has none: it discovers the server
  and registers itself, so the endpoint cannot be used as it is.
- `org.springaicommunity:mcp-authorization-server` solves this, but it is pre-1.0. An unstable API for the one part
  that decides who may get a client id is the wrong place to take that risk.
- The custom controller is about 150 lines and its rules fit in one class (`ClientRegistrationPolicy`) that is tested
  on its own: public clients only, grants within `{authorization_code, refresh_token}`, scope within `{docs.read}`,
  `https` or loopback redirects. Limits live in PostgreSQL so they hold across instances.

## Gaps in the framework, closed in code

| Gap | Fix |
|---|---|
| No refresh token for a public client on the code grant | `PublicClientRefreshTokenGenerator`. Rotation and replay detection are what make it safe |
| No client authentication for a public client on the refresh grant and on revocation | `PublicClientRefreshAuthentication` (converter and provider), ordered before the PKCE provider |
| `localhost` redirects need an exact port match (only IP literals vary) | `LoopbackRedirectValidator`: for `localhost`, only the port may differ |
| A retired refresh token is just "not found" | `RefreshTokenReplayGuard` stores its SHA-256 and revokes the family when it is presented, except inside a short grace window (default 10 s) so the loser of a parallel refresh does not sign the winner out |
| The JDBC service has no compare-and-set, so parallel refreshes both win | `RefreshTokenLockFilter`: the token request runs in one transaction behind `SELECT ... FOR UPDATE` |
| `resource` (RFC 8707) is ignored | `ResourceParameterFilter`: only the canonical `/mcp` URL is accepted |
| The metadata lists grants the framework can do | The customizer keeps only `authorization_code` and `refresh_token` |

## Consequences

- Spring Boot 4.1.2 and Spring AI 2.0.2 (built against Boot 4.1.x). The Spring BOMs are imported in the module, not
  in the root pom, so modules 01 and 02 keep resolving against the root BOMs.
- The module uses Jackson 3 (`tools.jackson`) through Boot 4, next to the Jackson 2 line of the other modules.
  They share only `jackson-annotations`.
- Token values are stored in plain text by the JDBC store. Hashing at rest is out of scope and noted in the README.
