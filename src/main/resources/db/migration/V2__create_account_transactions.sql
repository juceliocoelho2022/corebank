CREATE TABLE account_transactions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    balance_before NUMERIC(19,2) NOT NULL,
    balance_after NUMERIC(19,2) NOT NULL,
    description VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_account_transactions_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT ck_account_transactions_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT ck_account_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_account_transactions_balance_before_non_negative CHECK (balance_before >= 0),
    CONSTRAINT ck_account_transactions_balance_after_non_negative CHECK (balance_after >= 0)
);

CREATE INDEX idx_account_transactions_account_created_at ON account_transactions (account_id, created_at DESC);
