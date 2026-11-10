CREATE TABLE documents (
    id bigserial PRIMARY KEY,
    title text NOT NULL,
    body text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX documents_fts_idx ON documents
    USING gin (to_tsvector('english', title || ' ' || body));

INSERT INTO documents (title, body, updated_at) VALUES
('Model Context Protocol: trust boundaries',
 'An MCP server exposes tools to a client that a model drives. Three boundaries matter. The user trusts the client. The client trusts the server only as far as the user chose to connect it. The model trusts nothing it reads, because tool descriptions and tool results are text that ends up in its context. A description written by a hostile server can carry instructions, so a client should show descriptions to the user and treat tool output as data, never as commands. A server, in turn, must never put document content into its own tool descriptions. Transport adds a fourth boundary. A remote server needs authentication on every request, a check of the Origin header against DNS rebinding, and tokens that are short lived and bound to the server they were issued for.',
 '2026-01-10T09:00:00Z'),
('OAuth 2.1 for public clients',
 'A public client cannot keep a secret, so it proves possession of the authorization code with PKCE. The client creates a random code verifier, sends its SHA-256 hash as the code challenge, and later sends the verifier when it exchanges the code. Only the S256 method is acceptable. Access tokens are short lived. Refresh tokens rotate: each refresh returns a new refresh token, and presenting an old one is evidence of theft, so the server revokes the whole family. Redirect URIs for native clients are loopback addresses where any port is accepted.',
 '2026-01-12T09:00:00Z'),
('Why authorize on every read',
 'A token that was valid when the request started can be revoked before the request ends. Paginated reads make the window longer, because a client reads page one, the user revokes access, and the client asks for page two. If the server only checks the token at the edge, page two still succeeds from a cursor issued earlier. Checking the authorization record on each call makes revocation take effect on the next request, at the price of one lookup per call.',
 '2026-01-15T09:00:00Z'),
('The lighthouse keeper',
 'The lamp had burned for forty years and the keeper had never once seen it from the sea. On the night of the great storm he rowed out beyond the rocks to look back. From there the beam swept the water in long slow arcs, and he understood for the first time what the ships saw: not a light, but a rhythm they could steer by. He rowed home against the tide and cleaned the lens until morning. This text was written for this repository and is free to use.',
 '2026-02-01T09:00:00Z'),
('A short guide to pagination',
 'Offset pagination is simple and drifts when rows change. Keyset pagination remembers the last key seen and asks for the rows after it, so it stays stable and fast. Either way a cursor given to a client should be opaque and signed. Opaque means the client cannot build one by hand. Signed means the server can tell a cursor it issued from one that was edited or copied from another endpoint.',
 '2026-02-03T09:00:00Z'),
('Rotation and replay of refresh tokens',
 'When a refresh token is used the server returns a new access token and a new refresh token and retires the old one. The retired token is remembered as a hash. If it shows up again, either the legitimate client or a thief holds a copy, and the server cannot tell which, so it revokes the authorization and both must sign in again. Two refresh requests with the same token at the same moment must resolve to exactly one winner, which needs a row lock.',
 '2026-02-05T09:00:00Z'),
('Moby-Dick, chapter 1 (excerpt)',
 'Call me Ishmael. Some years ago, never mind how long precisely, having little or no money in my purse, and nothing particular to interest me on shore, I thought I would sail about a little and see the watery part of the world. It is a way I have of driving off the spleen and regulating the circulation. Whenever I find myself growing grim about the mouth; whenever it is a damp, drizzly November in my soul; then, I account it high time to get to sea as soon as I can. Herman Melville, 1851, public domain.',
 '2026-02-10T09:00:00Z'),
('Pride and Prejudice, opening (excerpt)',
 'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife. However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered the rightful property of some one or other of their daughters. Jane Austen, 1813, public domain.',
 '2026-02-11T09:00:00Z');
