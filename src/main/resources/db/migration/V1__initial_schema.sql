-- Implements docs/04-data-model.md from url-shortener-kb.
-- Where this file and that document disagree, the document is right.

CREATE TABLE owners (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness while preserving the casing the Owner typed.
CREATE UNIQUE INDEX owners_email_lower_key ON owners (lower(email));


CREATE TABLE links (
    id              bigserial   PRIMARY KEY,
    code            varchar(32) NOT NULL,
    destination     text        NOT NULL,
    owner_id        uuid        NOT NULL REFERENCES owners (id),
    is_custom_alias boolean     NOT NULL DEFAULT false,
    status          varchar(16) NOT NULL DEFAULT 'ACTIVE',
    click_count     bigint      NOT NULL DEFAULT 0,
    expires_at      timestamptz NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz NULL,

    CONSTRAINT links_status_check
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT links_destination_scheme_check
        CHECK (destination ~* '^https?://')
);

-- The Code Namespace. DELIBERATELY NOT PARTIAL on deleted_at: a deleted Link keeps
-- its code forever. Making this `WHERE deleted_at IS NULL` would let a stranger claim
-- a code that is already printed on someone's poster (ADR-0008).
--
-- This index is also the collision detector for ADR-0002: code generation does not
-- check availability first, it inserts and handles the unique violation.
CREATE UNIQUE INDEX links_code_key ON links (code);

-- Dashboard listing: an Owner's live Links, newest first.
CREATE INDEX links_owner_created_idx
    ON links (owner_id, created_at DESC)
    WHERE deleted_at IS NULL;


-- Append-only. What makes a mutable Destination defensible rather than a loophole:
-- "someone could bait-and-switch a shared link" is answered with "and every switch is
-- recorded, with who and when" (ADR-0009).
CREATE TABLE link_destination_history (
    id              bigserial   PRIMARY KEY,
    link_id         bigint      NOT NULL REFERENCES links (id),
    old_destination text        NOT NULL,
    new_destination text        NOT NULL,
    changed_by      uuid        NOT NULL REFERENCES owners (id),
    changed_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ldh_link_idx ON link_destination_history (link_id, changed_at DESC);


-- NOTE: there is no raw IP column here, and none will be added. ip_hash is
-- SHA-256(ip + salt) — enough to spot the same Visitor twice, useless for identifying
-- them, harmless in a leak (02-nfr.md § Privacy).
CREATE TABLE click_events (
    id           bigserial    PRIMARY KEY,
    link_id      bigint       NOT NULL REFERENCES links (id),
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    referrer     text         NULL,
    user_agent   varchar(512) NULL,
    country_code char(2)      NOT NULL DEFAULT 'XX',
    device_type  varchar(16)  NOT NULL DEFAULT 'UNKNOWN',
    ip_hash      char(64)     NULL,

    CONSTRAINT click_device_check
        CHECK (device_type IN ('DESKTOP', 'MOBILE', 'TABLET', 'BOT', 'UNKNOWN'))
);

CREATE INDEX click_events_link_time_idx ON click_events (link_id, occurred_at DESC);
