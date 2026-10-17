CREATE SEQUENCE account_number_seq
    START WITH 10000001
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    document VARCHAR(64) NOT NULL,
    email VARCHAR(254) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_customers_document UNIQUE (document),
    CONSTRAINT ck_customers_name_length CHECK (char_length(name) BETWEEN 3 AND 120)
);

CREATE UNIQUE INDEX uk_customers_email_lower ON customers (lower(email));

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    branch VARCHAR(4) NOT NULL DEFAULT '0001',
    number VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    balance NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'BRL',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_accounts_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT uk_accounts_number UNIQUE (number),
    CONSTRAINT ck_accounts_branch CHECK (branch = '0001'),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_accounts_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'BRL')
);

CREATE INDEX idx_accounts_customer_id ON accounts(customer_id);
