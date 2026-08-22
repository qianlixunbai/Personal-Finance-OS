ALTER TABLE transaction_import_batches
    DISABLE TRIGGER trg_transaction_import_batches_immutable;

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
                  WHEN mod(extract(microseconds FROM batch.confirmed_at)::BIGINT, 1000) = 0
                      THEN to_char(batch.confirmed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
                  ELSE to_char(batch.confirmed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
              END
           || '|' || reference.references || '|' || impact.impacts AS value
    FROM transaction_import_batches batch
    JOIN references_by_batch reference ON reference.batch_id = batch.id
    JOIN impacts_by_batch impact ON impact.batch_id = batch.id
)
UPDATE transaction_import_batches batch
SET result_digest = encode(digest(receipt_input.value, 'sha256'), 'hex')
FROM receipt_input
WHERE batch.id = receipt_input.id
  AND batch.result_digest <> encode(digest(receipt_input.value, 'sha256'), 'hex');

ALTER TABLE transaction_import_batches
    ENABLE TRIGGER trg_transaction_import_batches_immutable;
