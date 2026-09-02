-- PLATFORM-14 · 001-product-family-catalog · the one schema statement.
--
-- primary-keys, "The schema default is the backstop, and the banned generator is named
-- beside it": the key column carries NO column default. Native uuidv7() is a PostgreSQL 18
-- function and this deployment is pinned to 16 (lld D-13), so no native generator exists and
-- the backstop is absent — a named gap, recorded in lld §3 and §7, not assumed away.
-- gen_random_uuid() is banned by name and appears nowhere in this file.
--
-- java-backend-rules, "Clock is injected", layer clause: no DEFAULT now(), no trigger. Both
-- timestamp columns are written from the injected Clock in application code.
--
-- Lock and rewrite hazards: one CREATE TABLE plus one unique index on an empty table. The
-- non-concurrent index build is the one operation a migration lint would flag; the lint is
-- not wired (plan §9) and the justification — a new, empty table — is recorded in lld §3.

CREATE TABLE product_family (
    id          uuid         NOT NULL,
    family_code varchar(20)  NOT NULL,
    name        varchar(120) NOT NULL,
    status      varchar(16)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT pk_product_family PRIMARY KEY (id),
    CONSTRAINT ck_product_family_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX ux_product_family_code ON product_family (family_code);
