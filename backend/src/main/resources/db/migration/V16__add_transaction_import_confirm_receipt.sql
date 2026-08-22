ALTER TABLE transaction_import_sessions
    ADD CONSTRAINT uk_transaction_import_sessions_user_id_id UNIQUE (user_id, id);

ALTER TABLE transaction_import_batches
    ADD COLUMN session_id UUID NOT NULL,
    ADD COLUMN result_digest CHAR(64) NOT NULL,
    ADD CONSTRAINT uk_transaction_import_batches_user_session UNIQUE (user_id, session_id),
    ADD CONSTRAINT fk_transaction_import_batches_owned_session FOREIGN KEY (user_id, session_id)
        REFERENCES transaction_import_sessions (user_id, id),
    ADD CONSTRAINT ck_transaction_import_batches_result_digest CHECK (result_digest ~ '^[0-9a-f]{64}$');

ALTER TABLE transaction_import_items
    ALTER COLUMN created_transaction_id SET NOT NULL;

CREATE TABLE transaction_import_batch_account_impacts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    batch_id UUID NOT NULL,
    account_id BIGINT NOT NULL,
    row_count INTEGER NOT NULL,
    balance_before NUMERIC(18, 2) NOT NULL,
    delta NUMERIC(18, 2) NOT NULL,
    balance_after NUMERIC(18, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_transaction_import_impacts_owned_batch FOREIGN KEY (user_id, batch_id)
        REFERENCES transaction_import_batches (user_id, id),
    CONSTRAINT fk_transaction_import_impacts_owned_account FOREIGN KEY (user_id, account_id)
        REFERENCES accounts (user_id, id),
    CONSTRAINT uk_transaction_import_impacts_batch_account UNIQUE (batch_id, account_id),
    CONSTRAINT ck_transaction_import_impacts_row_count CHECK (row_count > 0),
    CONSTRAINT ck_transaction_import_impacts_balance_after CHECK (balance_after = balance_before + delta)
);

CREATE OR REPLACE FUNCTION reject_transaction_import_receipt_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'transaction import receipt data is immutable' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transaction_import_batches_immutable
    BEFORE UPDATE OR DELETE ON transaction_import_batches
    FOR EACH ROW EXECUTE FUNCTION reject_transaction_import_receipt_mutation();

CREATE TRIGGER trg_transaction_import_items_immutable
    BEFORE UPDATE OR DELETE ON transaction_import_items
    FOR EACH ROW EXECUTE FUNCTION reject_transaction_import_receipt_mutation();

CREATE TRIGGER trg_transaction_import_impacts_immutable
    BEFORE UPDATE OR DELETE ON transaction_import_batch_account_impacts
    FOR EACH ROW EXECUTE FUNCTION reject_transaction_import_receipt_mutation();
