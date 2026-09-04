-- Implements docs/04-data-model.md § api_keys. See ADR-0019.

CREATE TABLE api_keys (
    id           bigserial   PRIMARY KEY,
    owner_id     uuid        NOT NULL REFERENCES owners (id) ON DELETE CASCADE,
    name         varchar(64) NOT NULL,
    -- SHA-256 of the plaintext, and the lookup key: authentication hashes what arrived
    -- and finds the row, or answers 401. bcrypt cannot do this — its per-row salt means
    -- no index is possible and every candidate row would have to be compared one by one.
    key_hash     char(64)    NOT NULL,
    -- The plaintext is gone after creation, so an Owner with three keys needs enough to
    -- tell which one to revoke. Enough to recognise, never enough to use.
    key_prefix   varchar(16) NOT NULL,
    key_last4    char(4)     NOT NULL,
    -- Answers the only question worth asking before revoking: is anything still using
    -- this? Written best-effort on use; never worth failing a request over.
    last_used_at timestamptz NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- Kept, not deleted. A key that was used is evidence, and dropping the row would
    -- erase last_used_at along with it.
    revoked_at   timestamptz NULL
);

CREATE UNIQUE INDEX api_keys_hash_key ON api_keys (key_hash);

CREATE INDEX api_keys_owner_idx
    ON api_keys (owner_id, created_at DESC)
    WHERE revoked_at IS NULL;
