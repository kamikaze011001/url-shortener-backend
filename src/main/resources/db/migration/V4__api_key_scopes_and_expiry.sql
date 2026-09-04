-- Implements docs/04-data-model.md § api_keys. See ADR-0020.

-- Added without a default, then backfilled, then made NOT NULL — three steps rather
-- than one so the backfill is a decision written down rather than a DEFAULT clause
-- nobody reads.
ALTER TABLE api_keys ADD COLUMN scopes text[];

-- Every key that already exists gets every scope: exactly the authority it has today.
-- A migration must never narrow a credential that is already in use — the same rule
-- V2 applied when it backfilled email_verified to true for existing Owners.
UPDATE api_keys SET scopes = ARRAY['links:read', 'links:write'] WHERE scopes IS NULL;

ALTER TABLE api_keys ALTER COLUMN scopes SET NOT NULL;

-- Makes an unknown scope unstorable even by a hand-written INSERT that never passes
-- through the application. A CHECK rather than a lookup table because the set is small,
-- fixed, always read with the key, and never queried across keys — the normalised shape
-- would buy nothing here.
ALTER TABLE api_keys ADD CONSTRAINT api_keys_scopes_known CHECK (
    cardinality(scopes) > 0
    AND scopes <@ ARRAY['links:read', 'links:write']
);

-- NULL means never, which is what every existing key is. Nullable on purpose: expiry is
-- chosen, not imposed. A key that dies on a schedule breaks an unattended integration at
-- an hour nobody is awake, and ADR-0020 does not pretend otherwise.
ALTER TABLE api_keys ADD COLUMN expires_at timestamptz NULL;

-- No index. Expiry is only ever read alongside a key already found by its hash, so this
-- column is a filter on one row, never a search key.
