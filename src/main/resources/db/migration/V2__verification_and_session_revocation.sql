-- Implements docs/04-data-model.md from url-shortener-kb at the revision in
-- contracts/REVISION. Where this file and that document disagree, the document is right.

ALTER TABLE owners
    ADD COLUMN email_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN token_version  integer NOT NULL DEFAULT 0;

-- Accounts that already exist are verified. They were created before verification
-- existed and were never verified under the old rules either, so locking them out now
-- would break working accounts for no security gain. New registrations start false.
UPDATE owners SET email_verified = true;


-- One row per outstanding One-Time Code, for both purposes.
--
-- Postgres and not Redis, and that follows from ADR-0004: Redis holds a cache and rate
-- limits and is never a source of truth. A code that authorises a password change is
-- one. `attempts` living here is the sharper half of the argument — a brute-force limit
-- that resets when a container restarts is not a limit.
CREATE TABLE otp_codes (
    id          bigserial   PRIMARY KEY,
    owner_id    uuid        NOT NULL REFERENCES owners (id) ON DELETE CASCADE,
    purpose     varchar(24) NOT NULL,
    -- SHA-256 of the six digits. A database leak must not hand over live codes.
    code_hash   char(64)    NOT NULL,
    attempts    smallint    NOT NULL DEFAULT 0,
    expires_at  timestamptz NOT NULL,
    -- Marked rather than deleted: a consumed code is evidence, and the case worth
    -- being able to see is the same code arriving twice.
    consumed_at timestamptz NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT otp_purpose_check
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);

-- The only lookup: the newest live code for this Owner and purpose.
CREATE INDEX otp_codes_owner_purpose_idx
    ON otp_codes (owner_id, purpose, created_at DESC)
    WHERE consumed_at IS NULL;
