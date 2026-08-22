CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE transaction_import_sessions
    ADD CONSTRAINT uk_transaction_import_sessions_user_id_id UNIQUE (user_id, id);

ALTER TABLE transaction_import_batches
    ADD COLUMN session_id UUID,
    ADD COLUMN result_digest CHAR(64);

UPDATE transaction_import_batches batch
SET session_id = session.id
FROM transaction_import_sessions session
WHERE session.user_id = batch.user_id
  AND session.preallocated_batch_id = batch.id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM transaction_import_batches WHERE session_id IS NULL) THEN
        RAISE EXCEPTION 'cannot upgrade transaction import batch without its V15 preallocated session binding';
    END IF;
    IF EXISTS (SELECT 1 FROM transaction_import_items WHERE created_transaction_id IS NULL) THEN
        RAISE EXCEPTION 'cannot upgrade transaction import item without its created transaction binding';
    END IF;
END;
$$;

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

WITH imported_deltas AS (
    SELECT item.user_id, item.batch_id, transaction.account_id, COUNT(*)::INTEGER AS row_count,
           SUM(CASE WHEN transaction.type = 'EXPENSE' THEN -transaction.amount ELSE transaction.amount END)::NUMERIC(18, 2) AS delta
    FROM transaction_import_items item
    JOIN transactions transaction
      ON transaction.user_id = item.user_id AND transaction.id = item.created_transaction_id
    GROUP BY item.user_id, item.batch_id, transaction.account_id
)
INSERT INTO transaction_import_batch_account_impacts
        (user_id, batch_id, account_id, row_count, balance_before, delta, balance_after)
SELECT delta.user_id, delta.batch_id, delta.account_id, delta.row_count,
       (account.balance - delta.delta)::NUMERIC(18, 2), delta.delta, account.balance::NUMERIC(18, 2)
FROM imported_deltas delta
JOIN accounts account ON account.user_id = delta.user_id AND account.id = delta.account_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM transaction_import_batches batch
        WHERE NOT EXISTS (
            SELECT 1 FROM transaction_import_batch_account_impacts impact WHERE impact.batch_id = batch.id
        )
    ) THEN
        RAISE EXCEPTION 'cannot upgrade transaction import batch without account impact evidence';
    END IF;
END;
$$;

WITH references_by_batch AS (
    SELECT batch_id,
           '[' || string_agg(
                   'TransactionReference[rowNumber=' || source_row_number || ', transactionId=' || created_transaction_id || ']',
                   ', ' ORDER BY source_row_number) || ']' AS references
    FROM transaction_import_items
    GROUP BY batch_id
),
impacts_by_batch AS (
    SELECT batch_id,
           '[' || string_agg(
                   'AccountImpact[accountId=' || account_id || ', rowCount=' || row_count || ', balanceBefore=' || balance_before
                   || ', delta=' || delta || ', balanceAfter=' || balance_after || ']',
                   ', ' ORDER BY account_id) || ']' AS impacts
    FROM transaction_import_batch_account_impacts
    GROUP BY batch_id
),
receipt_input AS (
    SELECT batch.id,
           batch.session_id::text || '|' || batch.id::text || '|'
           || CASE
                  WHEN date_trunc('second', batch.confirmed_at) = batch.confirmed_at
                      THEN to_char(batch.confirmed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                  ELSE regexp_replace(
                      to_char(batch.confirmed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                      '(\\.\\d*?[1-9])0*Z$', '\\1Z')
              END
           || '|' || reference.references || '|' || impact.impacts AS value
    FROM transaction_import_batches batch
    JOIN references_by_batch reference ON reference.batch_id = batch.id
    JOIN impacts_by_batch impact ON impact.batch_id = batch.id
)
UPDATE transaction_import_batches batch
SET result_digest = encode(digest(receipt_input.value, 'sha256'), 'hex')
FROM receipt_input
WHERE batch.id = receipt_input.id;

ALTER TABLE transaction_import_batches
    ALTER COLUMN session_id SET NOT NULL,
    ALTER COLUMN result_digest SET NOT NULL,
    ADD CONSTRAINT uk_transaction_import_batches_user_session UNIQUE (user_id, session_id),
    ADD CONSTRAINT fk_transaction_import_batches_owned_session FOREIGN KEY (user_id, session_id)
        REFERENCES transaction_import_sessions (user_id, id),
    ADD CONSTRAINT ck_transaction_import_batches_result_digest CHECK (result_digest ~ '^[0-9a-f]{64}$');

ALTER TABLE transaction_import_items
    ALTER COLUMN created_transaction_id SET NOT NULL;

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
