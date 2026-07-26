-- Phase 2B-4A: immutable command receipts for idempotent BUY / SELL responses.
-- Existing non-opening facts cannot be upgraded safely because their original
-- account and position results are not reconstructable from current projections.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM investment_transactions
        WHERE transaction_type <> 'OPENING_POSITION'
    ) THEN
        RAISE EXCEPTION
            'V10 cannot add immutable investment write receipts: existing non-opening investment facts require unavailable historical response snapshots';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM investment_transactions
        WHERE idempotency_key <> btrim(idempotency_key)
           OR char_length(idempotency_key) NOT BETWEEN 1 AND 100
    ) THEN
        RAISE EXCEPTION
            'V10 cannot enforce canonical idempotency keys: existing invalid idempotency key found';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM investment_transactions
        WHERE request_hash !~ '^[0-9a-f]{64}$'
    ) THEN
        RAISE EXCEPTION
            'V10 cannot enforce SHA-256 request hashes: existing invalid request hash found';
    END IF;
END $$;

ALTER TABLE investment_transactions
    ADD COLUMN account_balance_after NUMERIC(18, 2),
    ADD COLUMN position_quantity_after NUMERIC(28, 8),
    ADD COLUMN position_avg_cost_after NUMERIC(28, 8),
    ADD COLUMN position_total_cost_after NUMERIC(28, 2),
    ADD COLUMN position_realized_profit_loss_after NUMERIC(28, 2),
    ADD COLUMN position_status_after VARCHAR(20),
    ADD COLUMN projection_version_after INTEGER,
    ADD CONSTRAINT ck_investment_transactions_request_hash_sha256
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_investment_transactions_idempotency_key_canonical
        CHECK (idempotency_key = btrim(idempotency_key) AND char_length(idempotency_key) BETWEEN 1 AND 100),
    ADD CONSTRAINT ck_investment_transactions_receipt
        CHECK (
            (transaction_type IN ('OPENING_POSITION', 'DIVIDEND')
                AND account_balance_after IS NULL
                AND position_quantity_after IS NULL
                AND position_avg_cost_after IS NULL
                AND position_total_cost_after IS NULL
                AND position_realized_profit_loss_after IS NULL
                AND position_status_after IS NULL
                AND projection_version_after IS NULL)
            OR
            (transaction_type IN ('BUY', 'SELL')
                AND account_balance_after IS NOT NULL
                AND position_quantity_after IS NOT NULL
                AND position_avg_cost_after IS NOT NULL
                AND position_total_cost_after IS NOT NULL
                AND position_realized_profit_loss_after IS NOT NULL
                AND position_status_after IN ('OPEN', 'CLOSED')
                AND projection_version_after > 0
                AND (
                    (position_quantity_after = 0
                        AND position_total_cost_after = 0
                        AND position_avg_cost_after = 0
                        AND position_status_after = 'CLOSED')
                    OR
                    (position_quantity_after > 0
                        AND position_total_cost_after > 0
                        AND position_status_after = 'OPEN')
                ))
        );
