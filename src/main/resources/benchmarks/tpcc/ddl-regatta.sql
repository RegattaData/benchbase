-- TPC-C schema adapted for Regatta SQL
-- Changes from the original PostgreSQL DDL:
--   1. decimal(p,s)   → NUMERIC(p,s)            (Regatta type name)
--   2. float          → DOUBLE PRECISION         (c_ytd_payment)
--   3. Composite PKs  → single-column PKs        (Regatta limitation)
--   4. FOREIGN KEY … REFERENCES … ON DELETE CASCADE  → removed (unsupported)
--   5. Table-level UNIQUE(…)    → column-level UNIQUE INDEX
--   6. PRIMARY KEY syntax       → PRIMARY KEY UNIQUE INDEX (mandatory INDEX keyword)
--   7. Standalone CREATE INDEX  → inline INDEX on column declaration
--   8. Multi-column index on customer(name) → single-column index on c_last only
--      (Regatta indexes are single-column)
--
-- Note: FK / cascade referential integrity must be enforced at the application layer.

-- ─── warehouse ──────────────────────────────────────────────────────────────
CREATE TABLE warehouse (
    w_id       INT              NOT NULL PRIMARY KEY UNIQUE INDEX,
    w_ytd      NUMERIC(12, 2)   NOT NULL,
    w_tax      NUMERIC(4, 4)    NOT NULL,
    w_name     VARCHAR(10)      NOT NULL,
    w_street_1 VARCHAR(20)      NOT NULL,
    w_street_2 VARCHAR(20)      NOT NULL,
    w_city     VARCHAR(20)      NOT NULL,
    w_state    CHAR(2)          NOT NULL,
    w_zip      CHAR(9)          NOT NULL
);

-- ─── item ────────────────────────────────────────────────────────────────────
CREATE TABLE item (
    i_id    INT            NOT NULL PRIMARY KEY UNIQUE INDEX,
    i_name  VARCHAR(24)    NOT NULL,
    i_price NUMERIC(5, 2)  NOT NULL,
    i_data  VARCHAR(50)    NOT NULL,
    i_im_id INT            NOT NULL
);

-- ─── stock ───────────────────────────────────────────────────────────────────
-- Original PK was (s_w_id, s_i_id); Regatta requires single-column PK.
-- s_i_id chosen as PK; s_w_id uniqueness enforced by application.
CREATE TABLE stock (
    s_w_id       INT            NOT NULL,
    s_i_id       INT            NOT NULL PRIMARY KEY UNIQUE INDEX,
    s_quantity   INT            NOT NULL,
    s_ytd        NUMERIC(8, 2)  NOT NULL,
    s_order_cnt  INT            NOT NULL,
    s_remote_cnt INT            NOT NULL,
    s_data       VARCHAR(50)    NOT NULL,
    s_dist_01    CHAR(24)       NOT NULL,
    s_dist_02    CHAR(24)       NOT NULL,
    s_dist_03    CHAR(24)       NOT NULL,
    s_dist_04    CHAR(24)       NOT NULL,
    s_dist_05    CHAR(24)       NOT NULL,
    s_dist_06    CHAR(24)       NOT NULL,
    s_dist_07    CHAR(24)       NOT NULL,
    s_dist_08    CHAR(24)       NOT NULL,
    s_dist_09    CHAR(24)       NOT NULL,
    s_dist_10    CHAR(24)       NOT NULL
    -- FK (s_w_id) → warehouse(w_id) : removed, enforce at app layer
    -- FK (s_i_id) → item(i_id)      : removed, enforce at app layer
);

-- ─── district ────────────────────────────────────────────────────────────────
-- Original PK was (d_w_id, d_id); Regatta requires single-column PK.
CREATE TABLE district (
    d_w_id      INT             NOT NULL,
    d_id        INT             NOT NULL PRIMARY KEY UNIQUE INDEX,
    d_ytd       NUMERIC(12, 2)  NOT NULL,
    d_tax       NUMERIC(4, 4)   NOT NULL,
    d_next_o_id INT             NOT NULL,
    d_name      VARCHAR(10)     NOT NULL,
    d_street_1  VARCHAR(20)     NOT NULL,
    d_street_2  VARCHAR(20)     NOT NULL,
    d_city      VARCHAR(20)     NOT NULL,
    d_state     CHAR(2)         NOT NULL,
    d_zip       CHAR(9)         NOT NULL
    -- FK (d_w_id) → warehouse(w_id) : removed, enforce at app layer
);

-- ─── customer ────────────────────────────────────────────────────────────────
-- Original PK was (c_w_id, c_d_id, c_id); Regatta requires single-column PK.
-- float → DOUBLE PRECISION on c_ytd_payment.
-- Inline index on c_last replaces the original multi-column idx_customer_name.
CREATE TABLE customer (
    c_w_id         INT             NOT NULL,
    c_d_id         INT             NOT NULL,
    c_id           INT             NOT NULL PRIMARY KEY UNIQUE INDEX,
    c_discount     NUMERIC(4, 4)   NOT NULL,
    c_credit       CHAR(2)         NOT NULL,
    c_last         VARCHAR(16)     NOT NULL INDEX,   -- replaces idx_customer_name
    c_first        VARCHAR(16)     NOT NULL,
    c_credit_lim   NUMERIC(12, 2)  NOT NULL,
    c_balance      NUMERIC(12, 2)  NOT NULL,
    c_ytd_payment  DOUBLE PRECISION NOT NULL,        -- was float
    c_payment_cnt  INT             NOT NULL,
    c_delivery_cnt INT             NOT NULL,
    c_street_1     VARCHAR(20)     NOT NULL,
    c_street_2     VARCHAR(20)     NOT NULL,
    c_city         VARCHAR(20)     NOT NULL,
    c_state        CHAR(2)         NOT NULL,
    c_zip          CHAR(9)         NOT NULL,
    c_phone        CHAR(16)        NOT NULL,
    c_since        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    c_middle       CHAR(2)         NOT NULL,
    c_data         VARCHAR(500)    NOT NULL
    -- FK (c_w_id, c_d_id) → district(d_w_id, d_id) : removed, enforce at app layer
);

-- ─── history ─────────────────────────────────────────────────────────────────
-- No PK in original; heap table retained as-is.
CREATE TABLE history (
    h_c_id   INT             NOT NULL,
    h_c_d_id INT             NOT NULL,
    h_c_w_id INT             NOT NULL,
    h_d_id   INT             NOT NULL,
    h_w_id   INT             NOT NULL,
    h_date   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    h_amount NUMERIC(6, 2)   NOT NULL,
    h_data   VARCHAR(24)     NOT NULL
    -- FK (h_c_w_id, h_c_d_id, h_c_id) → customer : removed, enforce at app layer
    -- FK (h_w_id, h_d_id) → district  : removed, enforce at app layer
);

-- ─── oorder ──────────────────────────────────────────────────────────────────
-- Original PK was (o_w_id, o_d_id, o_id); also had UNIQUE(o_w_id, o_d_id, o_c_id, o_id).
-- Regatta single-column PK on o_id; composite UNIQUE not supported.
CREATE TABLE oorder (
    o_w_id       INT       NOT NULL,
    o_d_id       INT       NOT NULL,
    o_id         INT       NOT NULL PRIMARY KEY UNIQUE INDEX,
    o_c_id       INT       NOT NULL,
    o_carrier_id INT                DEFAULT NULL,
    o_ol_cnt     INT       NOT NULL,
    o_all_local  INT       NOT NULL,
    o_entry_d    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    -- FK (o_w_id, o_d_id, o_c_id) → customer : removed, enforce at app layer
    -- UNIQUE(o_w_id, o_d_id, o_c_id, o_id)   : dropped, multi-col UNIQUE unsupported
);

-- ─── new_order ───────────────────────────────────────────────────────────────
-- Original PK was (no_w_id, no_d_id, no_o_id); Regatta single-column PK on no_o_id.
CREATE TABLE new_order (
    no_w_id INT NOT NULL,
    no_d_id INT NOT NULL,
    no_o_id INT NOT NULL PRIMARY KEY UNIQUE INDEX
    -- FK (no_w_id, no_d_id, no_o_id) → oorder : removed, enforce at app layer
);

-- ─── order_line ──────────────────────────────────────────────────────────────
-- Original PK was (ol_w_id, ol_d_id, ol_o_id, ol_number); single-column on ol_number.
CREATE TABLE order_line (
    ol_w_id        INT            NOT NULL,
    ol_d_id        INT            NOT NULL,
    ol_o_id        INT            NOT NULL,
    ol_number      INT            NOT NULL PRIMARY KEY UNIQUE INDEX,
    ol_i_id        INT            NOT NULL,
    ol_delivery_d  TIMESTAMP               DEFAULT NULL,
    ol_amount      NUMERIC(6, 2)  NOT NULL,
    ol_supply_w_id INT            NOT NULL,
    ol_quantity    NUMERIC(6, 2)  NOT NULL,
    ol_dist_info   CHAR(24)       NOT NULL
    -- FK (ol_w_id, ol_d_id, ol_o_id) → oorder         : removed
    -- FK (ol_supply_w_id, ol_i_id)   → stock(s_w_id,s_i_id) : removed
);