-- Registration attempts, kept in the database so the limits hold across instances.
CREATE TABLE client_registration_event (
    id bigserial PRIMARY KEY,
    remote_ip varchar(64) NOT NULL,
    client_id varchar(100) NOT NULL,
    registered_at timestamptz NOT NULL
);
CREATE INDEX client_registration_event_ip_idx ON client_registration_event (remote_ip, registered_at);

-- SHA-256 of every refresh token that was rotated out. Presenting one again means the token
-- was copied, so the whole authorization (the token family) is revoked.
CREATE TABLE retired_refresh_token (
    token_sha256 char(64) PRIMARY KEY,
    authorization_id varchar(100) NOT NULL,
    retired_at timestamptz NOT NULL
);
