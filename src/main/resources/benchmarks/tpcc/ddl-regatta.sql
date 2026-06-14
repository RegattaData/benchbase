-- Tables are dropped (if needed) in BenchmarkModule.java before loading the DDL.
CREATE TABLE warehouse (
    w_id       INT              NOT NULL INDEX,
    w_ytd      NUMERIC(12, 2)   NOT NULL,
    w_tax      NUMERIC(4, 4)    NOT NULL,
    w_name     VARCHAR(10)      NOT NULL,
    w_street_1 VARCHAR(20)      NOT NULL,
    w_street_2 VARCHAR(20)      NOT NULL,
    w_city     VARCHAR(20)      NOT NULL,
    w_state    CHAR(2)          NOT NULL,
    w_zip      CHAR(9)          NOT NULL
);

CREATE TABLE item (
    i_id    INT            NOT NULL INDEX,
    i_name  VARCHAR(24)    NOT NULL,
    i_price NUMERIC(5, 2)  NOT NULL,
    i_data  VARCHAR(50)    NOT NULL,
    i_im_id INT            NOT NULL
);

CREATE TABLE stock (
    s_w_id       INT            NOT NULL,
    s_i_id       INT            NOT NULL,
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
    s_dist_10    CHAR(24)       NOT NULL,
    s_w_i_key    BIGINT         NOT NULL INDEX
);

CREATE TABLE district (
    d_w_id      INT             NOT NULL,
    d_id        INT             NOT NULL,
    d_ytd       NUMERIC(12, 2)  NOT NULL,
    d_tax       NUMERIC(4, 4)   NOT NULL,
    d_next_o_id INT             NOT NULL,
    d_name      VARCHAR(10)     NOT NULL,
    d_street_1  VARCHAR(20)     NOT NULL,
    d_street_2  VARCHAR(20)     NOT NULL,
    d_city      VARCHAR(20)     NOT NULL,
    d_state     CHAR(2)         NOT NULL,
    d_zip       CHAR(9)         NOT NULL,
    d_key       BIGINT          NOT NULL INDEX
);

CREATE TABLE customer (
    c_w_id         INT             NOT NULL,
    c_d_id         INT             NOT NULL,
    c_id           INT             NOT NULL,
    c_discount     NUMERIC(4, 4)   NOT NULL,
    c_credit       CHAR(2)         NOT NULL,
    c_last         VARCHAR(16)     NOT NULL,
    c_first        VARCHAR(16)     NOT NULL,
    c_credit_lim   NUMERIC(12, 2)  NOT NULL,
    c_balance      NUMERIC(12, 2)  NOT NULL,
    c_ytd_payment  DOUBLE PRECISION NOT NULL,
    c_payment_cnt  INT             NOT NULL,
    c_delivery_cnt INT             NOT NULL,
    c_street_1     VARCHAR(20)     NOT NULL,
    c_street_2     VARCHAR(20)     NOT NULL,
    c_city         VARCHAR(20)     NOT NULL,
    c_state        CHAR(2)         NOT NULL,
    c_state_key    INT             NOT NULL,
    c_zip          CHAR(9)         NOT NULL,
    c_phone        CHAR(16)        NOT NULL,
    c_since        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    c_middle       CHAR(2)         NOT NULL,
    c_data         VARCHAR(500)    NOT NULL,
    c_key          BIGINT          NOT NULL INDEX,
    c_w_d_last_first VARCHAR(64)   NOT NULL INDEX
);

CREATE TABLE history (
    h_c_id   INT             NOT NULL,
    h_c_d_id INT             NOT NULL,
    h_c_w_id INT             NOT NULL,
    h_d_id   INT             NOT NULL,
    h_w_id   INT             NOT NULL,
    h_date   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    h_amount NUMERIC(6, 2)   NOT NULL,
    h_data   VARCHAR(24)     NOT NULL,
    h_c_key  BIGINT          NOT NULL INDEX
);

CREATE TABLE oorder (
    o_w_id       INT       NOT NULL,
    o_d_id       INT       NOT NULL,
    o_id         INT       NOT NULL,
    o_c_id       INT       NOT NULL,
    o_carrier_id INT                DEFAULT NULL,
    o_ol_cnt     INT       NOT NULL,
    o_all_local  INT       NOT NULL,
    o_entry_d    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    o_key        BIGINT    NOT NULL INDEX,
    o_c_key      BIGINT    NOT NULL INDEX
);

CREATE TABLE new_order (
    no_w_id INT NOT NULL,
    no_d_id INT NOT NULL,
    no_o_id INT NOT NULL,
    no_key  BIGINT NOT NULL INDEX
);

CREATE TABLE order_line (
    ol_w_id        INT            NOT NULL,
    ol_d_id        INT            NOT NULL,
    ol_o_id        INT            NOT NULL,
    ol_number      INT            NOT NULL,
    ol_i_id        INT            NOT NULL,
    ol_delivery_d  TIMESTAMP               DEFAULT NULL,
    ol_amount      NUMERIC(6, 2)  NOT NULL,
    ol_supply_w_id INT            NOT NULL,
    ol_quantity    NUMERIC(6, 2)  NOT NULL,
    ol_dist_info   CHAR(24)       NOT NULL,
    ol_o_key       BIGINT         NOT NULL INDEX,
    ol_w_i_key     BIGINT         NOT NULL INDEX
);