-- region / nation / supplier schema adapted for Regatta SQL
-- Changes from the original PostgreSQL DDL:
--   1. PRIMARY KEY (col)  → col … PRIMARY KEY UNIQUE INDEX  (mandatory INDEX keyword)
--   2. FOREIGN KEY … REFERENCES … ON DELETE CASCADE → removed (unsupported)
--      Referential integrity (nation→region, supplier→nation) must be enforced
--      at the application layer.
-- All other types (INT, CHAR, VARCHAR, NUMERIC) are natively supported — no changes.

-- ─── region ──────────────────────────────────────────────────────────────────
CREATE TABLE region (
    r_regionkey INT       NOT NULL PRIMARY KEY UNIQUE INDEX,
    r_name      CHAR(55)  NOT NULL,
    r_comment   CHAR(152) NOT NULL
);

-- ─── nation ──────────────────────────────────────────────────────────────────
CREATE TABLE nation (
    n_nationkey INT       NOT NULL PRIMARY KEY UNIQUE INDEX,
    n_name      CHAR(25)  NOT NULL,
    n_regionkey INT       NOT NULL,
    n_comment   CHAR(152) NOT NULL
    -- FK (n_regionkey) → region(r_regionkey) : removed, enforce at app layer
);

-- ─── supplier ────────────────────────────────────────────────────────────────
CREATE TABLE supplier (
    su_suppkey   INT             NOT NULL PRIMARY KEY UNIQUE INDEX,
    su_name      CHAR(25)        NOT NULL,
    su_address   VARCHAR(40)     NOT NULL,
    su_nationkey INT             NOT NULL,
    su_phone     CHAR(15)        NOT NULL,
    su_acctbal   NUMERIC(12, 2)  NOT NULL,
    su_comment   CHAR(101)       NOT NULL
    -- FK (su_nationkey) → nation(n_nationkey) : removed, enforce at app layer
);