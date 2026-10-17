ALTER TABLE account_transactions
    ADD COLUMN transfer_id UUID,
    ADD COLUMN counterparty_account_id UUID;

ALTER TABLE account_transactions
    ADD CONSTRAINT fk_account_transactions_counterparty
        FOREIGN KEY (counterparty_account_id) REFERENCES accounts(id);

ALTER TABLE account_transactions
    DROP CONSTRAINT ck_account_transactions_type;

ALTER TABLE account_transactions
    ADD CONSTRAINT ck_account_transactions_type
        CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_OUT', 'TRANSFER_IN'));

ALTER TABLE account_transactions
    ADD CONSTRAINT ck_account_transactions_transfer_metadata
        CHECK (
            (type IN ('DEPOSIT', 'WITHDRAWAL')
                AND transfer_id IS NULL
                AND counterparty_account_id IS NULL)
            OR
            (type IN ('TRANSFER_OUT', 'TRANSFER_IN')
                AND transfer_id IS NOT NULL
                AND counterparty_account_id IS NOT NULL
                AND account_id <> counterparty_account_id)
        );

CREATE INDEX idx_account_transactions_transfer_id
    ON account_transactions (transfer_id)
    WHERE transfer_id IS NOT NULL;
