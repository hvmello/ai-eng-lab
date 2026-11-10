# 03-mcp-server

A read-only [Model Context Protocol](https://modelcontextprotocol.io) server over Streamable HTTP, protected by OAuth 2.1.
It is the authorization server, the protected resource and the MCP server in one Spring Boot application, on
PostgreSQL. **No model is involved anywhere**, and the server is **off by default**.

Three tools, no resources, no prompts:

| Tool | Does |
|---|---|
| `list_documents(cursor?, limit?)` | Lists documents, with a signed, opaque `next_cursor` |
| `search_documents(query, cursor?, limit?)` | PostgreSQL full-text search, best match first |
| `read_document(id, cursor?)` | Reads a document in slices of at most `aienglab.mcp.page-chars` characters |

There is one scope, `docs.read`. Tool descriptions are static constants; document text only ever appears in a tool
result, as data.

## Run it

```
# 1. PostgreSQL
cp .env.example .env            # then fill it in, see below; .env is git-ignored
docker compose --env-file ../.env -f compose.yaml up -d

# 2. Credentials: a bcrypt hash of the password, and a random cursor key
#    (no default user or password is committed; startup fails without them)
#    MCP_USER, MCP_USER_PASSWORD_HASH, MCP_CURSOR_KEY, MCP_DB_URL, MCP_DB_USER, MCP_DB_PASSWORD

# 3. Start it
./mvnw -B -q -pl 03-mcp-server spring-boot:run -Dspring-boot.run.arguments=--aienglab.mcp.enabled=true
```

Without `--aienglab.mcp.enabled=true` (or `AIENGLAB_MCP_ENABLED=true`), `main` logs one line and exits with code 0
before it opens a port or touches the database. The server binds to `127.0.0.1:8080`. `MCP_BASE_URL` is the canonical
origin and must match what clients use (the protected resource is `<MCP_BASE_URL>/mcp`).

Connect Claude Code:

```
claude mcp add --transport http docs http://127.0.0.1:8080/mcp
```

Claude Code finds the authorization server from the `WWW-Authenticate` header, registers itself, opens the browser for
login and consent, and stores the tokens.

## What is built

| Part | How |
|---|---|
| Discovery | `/.well-known/oauth-protected-resource[/mcp]` (RFC 9728, Spring Security 7) and `/.well-known/oauth-authorization-server` (RFC 8414). Unauthenticated `/mcp` gets `401` with `WWW-Authenticate: Bearer resource_metadata="..."` |
| Flow | Authorization code, PKCE `S256` required, public clients only, login form and consent screen for the one configured user |
| Tokens | Opaque (reference) tokens. Access: 5 min. Refresh: 8 h, rotated on every use |
| Registration | `POST /register` (RFC 7591): `token_endpoint_auth_method=none`, grants within `{authorization_code, refresh_token}`, scope within `{docs.read}`, `https` or loopback redirects. Limits in PostgreSQL: 10 per IP per hour, 200 clients in total |
| Replay | A reused code revokes what it issued. A reused refresh token revokes the whole family. N parallel refreshes with one token: exactly one wins (row lock) |
| Every read | The bearer token is looked up in the database on every request, and `docs.read` is checked again in the tool, so a revoked client stops on its next call, including mid-pagination |
| Revocation | `/oauth2/revoke` (RFC 7009), also for public clients |
| Resource parameter | RFC 8707: `resource` is accepted only when it is the canonical `/mcp` URL |
| Origin | `Origin` and `Host` are validated on `/mcp` against DNS rebinding (loopback and the configured base URL) |

## What the automated tests prove

`./mvnw verify` runs 55 unit tests and 42 integration tests. The integration tests (`*IT`, failsafe) start the whole
application on a real port against a disposable PostgreSQL from Testcontainers, so **Docker must be running**
(`-DskipITs` skips them, for local use only; CI runs them). Credentials are generated at run time.

| Claim | Test |
|---|---|
| register, login, authorize with PKCE, exchange, `initialize`, `tools/list`, every tool | `McpFlowIT` |
| missing PKCE, the `plain` method and a wrong verifier are rejected | `McpFlowIT` |
| replaying a code gives `invalid_grant` and kills the first access token | `McpFlowIT` |
| access token expires (injected clock) | `McpFlowIT` |
| `scope=admin` cannot be registered, extra scope on authorize and on refresh is `invalid_scope`, `tools/list` is exactly three tools, resources and prompts are empty | `McpFlowIT` |
| `401` with `resource_metadata`, metadata documents, bad `Origin` gets `403`, `resource` must be the MCP URL | `McpFlowIT` |
| refresh rotates, replaying a retired token revokes the family, another client's token is refused, expiry | `RefreshAndRevocationIT` |
| 8 parallel refreshes with one token: exactly one succeeds (fails with 8 successes when the row lock is removed) | `RefreshAndRevocationIT` |
| reads on 6 threads, then revoke: nothing started after revoke returned is served; page 1, revoke, page 2 fails | `RefreshAndRevocationIT` |
| per-IP and global limits trip, also under 20 parallel registrations; confidential auth methods, non-loopback `http` redirects and extra grants are rejected | `RegistrationLimitsIT` |
| off by default: `main` returns without listening on 8080; enabled without credentials fails startup | `OffByDefaultTest` |
| cursors: round trip, tampering, other key, other tool, other query; limits capped; every registration rule | `CursorTest`, `DocumentToolsTest`, `ClientRegistrationPolicyTest` |

## What still needs manual validation

Nothing here was run against a real MCP client. **Not yet validated:**

| Client | Status |
|---|---|
| Claude Code (`claude mcp add --transport http`) | **Not yet validated.** Expected to work: it registers a `localhost` callback with a random port (the redirect check accepts any port for loopback hosts, including `localhost`) and sends `resource`. Whether it sends exactly `<base>/mcp` can only be confirmed by running it |
| Claude Desktop, Cursor, VS Code, MCP Inspector | Not yet validated |

Other gaps to know about:

- Token values are stored in plain text by Spring's JDBC store. Acceptable for a lab, not for production, where they
  would be hashed at rest.
- Revocation stops the server from answering. It cannot remove what the client already read: see `LEARNING.md`.
- There is one user and no brute-force protection on the login form.
- No TLS, reverse proxy or deployment. `X-Forwarded-For` is not trusted for the per-IP limit, so behind a proxy all
  clients would share one address.
- `Origin` is validated inside the MCP handler, so an unauthenticated request with a bad `Origin` is answered `401`
  before it is answered `403`.
- Retired refresh token hashes are kept forever in `retired_refresh_token`.

## Running the tests where loopback traffic is filtered

On one Windows machine with a network filter driver, small HTTP responses from a Java server on loopback were
rewritten on the wire (a `transfer-encoding: chunked` header added to an unchunked body). The server's own response was
correct at the servlet level. The integration tests passed unchanged inside a Linux container with the Docker socket
mounted. This does not affect CI.
