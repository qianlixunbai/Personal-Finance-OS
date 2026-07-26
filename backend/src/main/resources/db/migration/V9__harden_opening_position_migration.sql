-- Phase 2B-3: schema safeguards only. User-triggered opening migrations remain application-level.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM investment_transactions
        WHERE transaction_type = 'OPENING_POSITION'
          AND status = 'POSTED'
        GROUP BY user_id, asset_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V9 cannot enforce one posted opening position per asset: duplicate posted opening facts found';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM investment_transactions transaction_row
        JOIN assets asset_row
          ON asset_row.user_id = transaction_row.user_id
         AND asset_row.id = transaction_row.asset_id
        WHERE asset_row.account_id IS NOT NULL
          AND transaction_row.account_id IS NOT NULL
          AND asset_row.account_id <> transaction_row.account_id
    ) THEN
        RAISE EXCEPTION
            'V9 cannot enforce transaction and asset account consistency: mismatched account bindings found';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM investment_transactions transaction_row
        JOIN assets asset_row
          ON asset_row.user_id = transaction_row.user_id
         AND asset_row.id = transaction_row.asset_id
        WHERE asset_row.account_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'V9 cannot enforce transaction and asset account consistency: transaction facts reference unbound assets';
    END IF;
END $$;

ALTER TABLE assets
    ADD CONSTRAINT uk_assets_user_id_id_account_id UNIQUE (user_id, id, account_id);

ALTER TABLE investment_transactions
    ADD CONSTRAINT fk_investment_transactions_user_asset_account
        FOREIGN KEY (user_id, asset_id, account_id)
        REFERENCES assets (user_id, id, account_id);

CREATE UNIQUE INDEX uk_investment_transactions_posted_opening_asset
    ON investment_transactions (user_id, asset_id)
    WHERE transaction_type = 'OPENING_POSITION'
      AND status = 'POSTED';
