-- Phase 2B-5A: append-only investment reversals. Monetary fact columns remain NUMERIC(28,2).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM investment_transactions
        WHERE status = 'REVERSED'
           OR reversed_at IS NOT NULL
           OR reversal_reason IS NOT NULL
           OR replaces_transaction_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'V12 cannot adopt append-only reversals: legacy reversal or replacement data exists';
    END IF;
END $$;

ALTER TABLE investment_transactions
    DROP CONSTRAINT ck_investment_transactions_type,
    DROP CONSTRAINT ck_investment_transactions_status,
    DROP CONSTRAINT ck_investment_transactions_source,
    DROP CONSTRAINT ck_investment_transactions_reversal_state,
    DROP CONSTRAINT ck_investment_transactions_type_fields,
    DROP CONSTRAINT ck_investment_transactions_receipt,
    ADD COLUMN original_transaction_id BIGINT,
    ADD COLUMN correction_reason VARCHAR(500),
    ADD COLUMN cash_delta NUMERIC(28, 2),
    ADD CONSTRAINT ck_investment_transactions_type CHECK (
        transaction_type IN ('BUY', 'SELL', 'DIVIDEND', 'OPENING_POSITION', 'REVERSAL')
    ),
    ADD CONSTRAINT ck_investment_transactions_status CHECK (status = 'POSTED'),
    ADD CONSTRAINT ck_investment_transactions_source CHECK (source IN ('MANUAL', 'MIGRATION', 'IMPORT', 'CORRECTION')),
    ADD CONSTRAINT ck_investment_transactions_legacy_correction_fields_empty CHECK (
        replaces_transaction_id IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL
    ),
    ADD CONSTRAINT ck_investment_transactions_type_fields CHECK (
        (transaction_type IN ('BUY', 'SELL', 'OPENING_POSITION') AND quantity IS NOT NULL AND unit_price IS NOT NULL)
        OR (transaction_type = 'DIVIDEND' AND quantity IS NULL AND unit_price IS NULL)
        OR (transaction_type = 'REVERSAL' AND ((quantity IS NULL AND unit_price IS NULL) OR (quantity IS NOT NULL AND unit_price IS NOT NULL)))
    ),
    ADD CONSTRAINT ck_investment_transactions_correction_fields CHECK (
        (transaction_type = 'REVERSAL'
            AND source = 'CORRECTION'
            AND original_transaction_id IS NOT NULL
            AND cash_delta IS NOT NULL
            AND correction_reason = btrim(correction_reason)
            AND char_length(correction_reason) BETWEEN 1 AND 500)
        OR (transaction_type <> 'REVERSAL'
            AND source <> 'CORRECTION'
            AND original_transaction_id IS NULL
            AND correction_reason IS NULL
            AND cash_delta IS NULL)
    ),
    ADD CONSTRAINT uk_investment_transactions_user_account_asset_id UNIQUE (user_id, account_id, asset_id, id),
    ADD CONSTRAINT fk_investment_transactions_original_binding
        FOREIGN KEY (user_id, account_id, asset_id, original_transaction_id)
        REFERENCES investment_transactions (user_id, account_id, asset_id, id);

ALTER TABLE investment_transactions
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
            (transaction_type IN ('BUY', 'SELL', 'DIVIDEND', 'REVERSAL')
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

CREATE UNIQUE INDEX uk_investment_transactions_reversal_original
    ON investment_transactions (user_id, original_transaction_id)
    WHERE transaction_type = 'REVERSAL';

CREATE OR REPLACE FUNCTION validate_append_only_investment_reversal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    original_row investment_transactions%ROWTYPE;
    expected_cash_delta NUMERIC(28, 2);
BEGIN
    IF NEW.transaction_type <> 'REVERSAL' THEN
        RETURN NEW;
    END IF;

    IF NEW.original_transaction_id = NEW.id THEN
        RAISE EXCEPTION 'investment reversal cannot reference itself' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO original_row
    FROM investment_transactions
    WHERE id = NEW.original_transaction_id
      AND user_id = NEW.user_id
      AND account_id = NEW.account_id
      AND asset_id = NEW.asset_id;

    IF NOT FOUND
       OR original_row.transaction_type NOT IN ('BUY', 'SELL', 'DIVIDEND')
       OR original_row.status <> 'POSTED'
       OR original_row.original_transaction_id IS NOT NULL
       OR original_row.replaces_transaction_id IS NOT NULL
       OR original_row.reversed_at IS NOT NULL
       OR original_row.reversal_reason IS NOT NULL THEN
        RAISE EXCEPTION 'investment reversal original is invalid' USING ERRCODE = '23514';
    END IF;

    expected_cash_delta := CASE original_row.transaction_type
        WHEN 'BUY' THEN original_row.net_amount
        ELSE original_row.net_amount * -1
    END;

    IF NEW.cash_delta IS DISTINCT FROM expected_cash_delta
       OR NEW.gross_amount IS DISTINCT FROM original_row.gross_amount
       OR NEW.fee_amount IS DISTINCT FROM original_row.fee_amount
       OR NEW.tax_amount IS DISTINCT FROM original_row.tax_amount
       OR NEW.net_amount IS DISTINCT FROM original_row.net_amount
       OR NEW.currency IS DISTINCT FROM original_row.currency
       OR NEW.released_cost_amount <> 0
       OR NEW.realized_profit_loss <> 0 THEN
        RAISE EXCEPTION 'investment reversal audit values are invalid' USING ERRCODE = '23514';
    END IF;

    IF original_row.transaction_type = 'DIVIDEND' THEN
        IF NEW.quantity IS NOT NULL OR NEW.unit_price IS NOT NULL THEN
            RAISE EXCEPTION 'dividend reversal quantity and unit price must be null' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.quantity IS DISTINCT FROM original_row.quantity
       OR NEW.unit_price IS DISTINCT FROM original_row.unit_price THEN
        RAISE EXCEPTION 'trade reversal quantity or unit price is invalid' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_append_only_investment_reversal
BEFORE INSERT ON investment_transactions
FOR EACH ROW EXECUTE FUNCTION validate_append_only_investment_reversal();

CREATE OR REPLACE FUNCTION prevent_investment_transaction_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'investment transaction facts are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_prevent_investment_transaction_mutation
BEFORE UPDATE OR DELETE ON investment_transactions
FOR EACH ROW EXECUTE FUNCTION prevent_investment_transaction_mutation();
