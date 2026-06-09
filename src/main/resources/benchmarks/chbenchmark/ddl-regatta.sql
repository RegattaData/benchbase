-- Tables are dropped (if needed) in BenchmarkModule.java before loading the DDL.
CREATE TABLE region (
    r_regionkey INT       NOT NULL,
    r_name      CHAR(55)  NOT NULL,
    r_comment   CHAR(152) NOT NULL
);

CREATE TABLE nation (
    n_nationkey INT       NOT NULL,
    n_name      CHAR(25)  NOT NULL,
    n_regionkey INT       NOT NULL,
    n_comment   CHAR(152) NOT NULL
);

CREATE TABLE supplier (
    su_suppkey   INT             NOT NULL,
    su_name      CHAR(25)        NOT NULL,
    su_address   VARCHAR(40)     NOT NULL,
    su_nationkey INT             NOT NULL,
    su_phone     CHAR(15)        NOT NULL,
    su_acctbal   NUMERIC(12, 2)  NOT NULL,
    su_comment   CHAR(101)       NOT NULL
);