-- Phase 2B-4B: DIVIDEND is an immutable posted cash fact with a complete receipt.
-- Historical dividend facts cannot be reconstructed safely, so upgrades fail before DDL.
DO $$
DECLARE
    receipt_definition TEXT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'investment_transactions'
          AND column_name IN (
              'account_balance_after', 'position_quantity_after', 'position_avg_cost_after',
              'position_total_cost_after', 'position_realized_profit_loss_after',
              'position_status_after', 'projection_version_after')
        GROUP BY table_name HAVING count(*) = 7
    ) THEN
        RAISE EXCEPTION 'V11 cannot harden dividend receipts: V10 receipt columns are missing';
    END IF;

    IF EXISTS (SELECT 1 FROM investment_transactions WHERE transaction_type = 'DIVIDEND') THEN
        RAISE EXCEPTION 'V11 cannot harden dividend receipts: existing dividend facts require unavailable historical response snapshots';
    END IF;

    SELECT pg_get_constraintdef(oid) INTO receipt_definition
    FROM pg_constraint
    WHERE conrelid = 'investment_transactions'::regclass
      AND conname = 'ck_investment_transactions_receipt';
    IF receipt_definition IS NULL
       OR receipt_definition NOT LIKE '%OPENING_POSITION%'
       OR receipt_definition NOT LIKE '%DIVIDEND%'
       OR receipt_definition NOT LIKE '%BUY%'
       OR receipt_definition NOT LIKE '%SELL%' THEN
        RAISE EXCEPTION 'V11 cannot harden dividend receipts: expected V10 receipt constraint is missing';
    END IF;

    IF EXISTS (
        SELECT 1 FROM investment_transactions
        WHERE transaction_type = 'OPENING_POSITION'
          AND (account_balance_after IS NOT NULL OR position_quantity_after IS NOT NULL
            OR position_avg_cost_after IS NOT NULL OR position_total_cost_after IS NOT NULL
            OR position_realized_profit_loss_after IS NOT NULL OR position_status_after IS NOT NULL
            OR projection_version_after IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'V11 cannot harden dividend receipts: opening position receipts must remain null';
    END IF;

    IF EXISTS (
        SELECT 1 FROM investment_transactions
        WHERE transaction_type IN ('BUY', 'SELL')
          AND NOT (
              account_balance_after IS NOT NULL
              AND position_quantity_after IS NOT NULL
              AND position_avg_cost_after IS NOT NULL
              AND position_total_cost_after IS NOT NULL
              AND position_realized_profit_loss_after IS NOT NULL
              AND position_status_after IN ('OPEN', 'CLOSED')
              AND projection_version_after > 0
              AND ((position_quantity_after = 0 AND position_total_cost_after = 0
                    AND position_avg_cost_after = 0 AND position_status_after = 'CLOSED')
                   OR (position_quantity_after > 0 AND position_total_cost_after > 0
                    AND position_avg_cost_after > 0 AND position_status_after = 'OPEN'))
          )
    ) THEN
        RAISE EXCEPTION 'V11 cannot harden dividend receipts: existing BUY or SELL receipt does not satisfy the new shape';
    END IF;
END $$;

ALTER TABLE investment_transactions
    DROP CONSTRAINT ck_investment_transactions_receipt,
    ADD CONSTRAINT ck_investment_transactions_receipt
        CHECK (
            (transaction_type = 'OPENING_POSITION'
                AND account_balance_after IS NULL
                AND position_quantity_after IS NULL
                AND position_avg_cost_after IS NULL
                AND position_total_cost_after IS NULL
                AND position_realized_profit_loss_after IS NULL
                AND position_status_after IS NULL
                AND projection_version_after IS NULL)
            OR
            (transaction_type IN ('BUY', 'SELL', 'DIVIDEND')
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
                        AND position_avg_cost_after > 0
                        AND position_status_after = 'OPEN')
                ))
        );
