DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM investment_transactions
        WHERE NOT (
            (transaction_type = 'BUY'
                AND quantity > 0 AND unit_price > 0 AND gross_amount > 0
                AND fee_amount >= 0 AND tax_amount >= 0
                AND net_amount = gross_amount + fee_amount + tax_amount
                AND released_cost_amount = 0 AND realized_profit_loss = 0)
            OR (transaction_type = 'SELL'
                AND quantity > 0 AND unit_price > 0 AND gross_amount > 0
                AND fee_amount >= 0 AND tax_amount >= 0
                AND net_amount = gross_amount - fee_amount - tax_amount AND net_amount >= 0
                AND released_cost_amount > 0
                AND realized_profit_loss = net_amount - released_cost_amount)
            OR (transaction_type = 'DIVIDEND'
                AND quantity IS NULL AND unit_price IS NULL AND gross_amount > 0
                AND fee_amount >= 0 AND tax_amount >= 0
                AND net_amount = gross_amount - fee_amount - tax_amount AND net_amount >= 0
                AND released_cost_amount = 0 AND realized_profit_loss = 0)
            OR (transaction_type = 'OPENING_POSITION'
                AND quantity > 0 AND unit_price > 0 AND gross_amount > 0
                AND fee_amount = 0 AND tax_amount = 0 AND net_amount = 0
                AND released_cost_amount = 0 AND realized_profit_loss = 0)
        )
    ) THEN
        RAISE EXCEPTION 'V5 cannot enforce investment transaction amount constraints: existing invalid transaction facts found';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM investment_transactions transaction_row
        LEFT JOIN investment_transactions replacement
            ON replacement.id = transaction_row.replaces_transaction_id
           AND replacement.user_id = transaction_row.user_id
           AND replacement.asset_id = transaction_row.asset_id
        WHERE transaction_row.replaces_transaction_id IS NOT NULL
          AND (transaction_row.replaces_transaction_id = transaction_row.id OR replacement.id IS NULL)
    ) THEN
        RAISE EXCEPTION 'V5 cannot enforce investment transaction replacement isolation: existing invalid replacement relation found';
    END IF;
END $$;

ALTER TABLE investment_transactions
    DROP CONSTRAINT investment_transactions_replaces_transaction_id_fkey,
    ADD CONSTRAINT uk_investment_transactions_user_asset_id UNIQUE (user_id, asset_id, id),
    ADD CONSTRAINT ck_investment_transactions_buy_amounts CHECK (
        transaction_type <> 'BUY'
        OR (gross_amount > 0
            AND net_amount = gross_amount + fee_amount + tax_amount
            AND released_cost_amount = 0
            AND realized_profit_loss = 0)
    ),
    ADD CONSTRAINT ck_investment_transactions_sell_amounts CHECK (
        transaction_type <> 'SELL'
        OR (gross_amount > 0
            AND net_amount = gross_amount - fee_amount - tax_amount
            AND net_amount >= 0
            AND released_cost_amount > 0
            AND realized_profit_loss = net_amount - released_cost_amount)
    ),
    ADD CONSTRAINT ck_investment_transactions_dividend_amounts CHECK (
        transaction_type <> 'DIVIDEND'
        OR (gross_amount > 0
            AND net_amount = gross_amount - fee_amount - tax_amount
            AND net_amount >= 0
            AND released_cost_amount = 0
            AND realized_profit_loss = 0)
    ),
    ADD CONSTRAINT ck_investment_transactions_opening_position_amounts_v5 CHECK (
        transaction_type <> 'OPENING_POSITION'
        OR (gross_amount > 0
            AND fee_amount = 0
            AND tax_amount = 0
            AND net_amount = 0
            AND released_cost_amount = 0
            AND realized_profit_loss = 0)
    ),
    ADD CONSTRAINT ck_investment_transactions_replacement_not_self CHECK (
        replaces_transaction_id IS NULL OR replaces_transaction_id <> id
    ),
    ADD CONSTRAINT fk_investment_transactions_replacement_user_asset
        FOREIGN KEY (user_id, asset_id, replaces_transaction_id)
        REFERENCES investment_transactions (user_id, asset_id, id);
