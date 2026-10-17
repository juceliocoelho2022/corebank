CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    code VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    account_id UUID NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_ledger_accounts_code UNIQUE (code),
    CONSTRAINT fk_ledger_accounts_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT ck_ledger_accounts_type CHECK (type IN ('CUSTOMER_LIABILITY', 'CASH_CLEARING')),
    CONSTRAINT ck_ledger_accounts_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_ledger_accounts_metadata CHECK (
        (type = 'CUSTOMER_LIABILITY' AND account_id IS NOT NULL)
        OR
        (type = 'CASH_CLEARING' AND account_id IS NULL)
    )
);

CREATE UNIQUE INDEX uk_ledger_accounts_customer_account
    ON ledger_accounts (account_id)
    WHERE type = 'CUSTOMER_LIABILITY';

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    reference_type VARCHAR(32) NOT NULL,
    reference_id UUID NOT NULL,
    description VARCHAR(200) NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_journal_entries_reference_type
        CHECK (reference_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'OPENING_BALANCE')),
    CONSTRAINT uk_journal_entries_reference UNIQUE (reference_type, reference_id)
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    journal_entry_id UUID NOT NULL,
    ledger_account_id UUID NOT NULL,
    side VARCHAR(8) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_ledger_entries_journal FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts(id),
    CONSTRAINT ck_ledger_entries_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_journal_id ON ledger_entries (journal_entry_id);
CREATE INDEX idx_ledger_entries_ledger_account_id ON ledger_entries (ledger_account_id);
CREATE INDEX idx_journal_entries_reference ON journal_entries (reference_type, reference_id);

INSERT INTO ledger_accounts (id, code, type, account_id, currency, created_at)
VALUES (gen_random_uuid(), 'TECH:CASH_CLEARING', 'CASH_CLEARING', NULL, 'BRL', CURRENT_TIMESTAMP);

INSERT INTO ledger_accounts (id, code, type, account_id, currency, created_at)
SELECT gen_random_uuid(), 'CUSTOMER:' || id::text, 'CUSTOMER_LIABILITY', id, currency, CURRENT_TIMESTAMP
FROM accounts;

WITH opening_journals AS (
    INSERT INTO journal_entries (id, reference_type, reference_id, description, created_at)
    SELECT gen_random_uuid(), 'OPENING_BALANCE', id, 'Opening balance migration', CURRENT_TIMESTAMP
    FROM accounts
    WHERE balance <> 0.00
    RETURNING id, reference_id
), opening_rows AS (
    SELECT oj.id AS journal_id, a.id AS account_id, a.balance
    FROM opening_journals oj
    JOIN accounts a ON a.id = oj.reference_id
)
INSERT INTO ledger_entries (id, journal_entry_id, ledger_account_id, side, amount, created_at)
SELECT gen_random_uuid(), r.journal_id, clearing.id, 'DEBIT', r.balance, CURRENT_TIMESTAMP
FROM opening_rows r
CROSS JOIN ledger_accounts clearing
WHERE clearing.code = 'TECH:CASH_CLEARING'
UNION ALL
SELECT gen_random_uuid(), r.journal_id, liability.id, 'CREDIT', r.balance, CURRENT_TIMESTAMP
FROM opening_rows r
JOIN ledger_accounts liability
  ON liability.account_id = r.account_id
 AND liability.type = 'CUSTOMER_LIABILITY';

CREATE OR REPLACE FUNCTION reject_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Ledger records are append-only'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_journal_entries_append_only
BEFORE UPDATE OR DELETE ON journal_entries
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER trg_ledger_entries_append_only
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
