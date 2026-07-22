ALTER TABLE accounts
    ADD CONSTRAINT uk_accounts_user_id_id UNIQUE (user_id, id);

ALTER TABLE assets
    ADD COLUMN account_id BIGINT,
    ADD COLUMN total_cost NUMERIC(28, 2),
    ADD COLUMN realized_profit_loss NUMERIC(28, 2),
    ADD COLUMN position_status VARCHAR(20),
    ADD COLUMN last_transaction_id BIGINT,
    ADD COLUMN projection_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN position_mode VARCHAR(30) NOT NULL DEFAULT 'LEGACY',
    ADD CONSTRAINT uk_assets_user_id_id UNIQUE (user_id, id),
    ADD CONSTRAINT fk_assets_user_account FOREIGN KEY (user_id, account_id)
        REFERENCES accounts (user_id, id),
    ADD CONSTRAINT ck_assets_position_mode CHECK (position_mode IN ('LEGACY', 'TRANSACTION_DRIVEN')),
    ADD CONSTRAINT ck_assets_position_status CHECK (position_status IS NULL OR position_status IN ('OPEN', 'CLOSED'));

CREATE TABLE investment_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    asset_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
    quantity NUMERIC(28, 8),
    unit_price NUMERIC(28, 8),
    gross_amount NUMERIC(28, 2) NOT NULL,
    fee_amount NUMERIC(28, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(28, 2) NOT NULL DEFAULT 0,
    net_amount NUMERIC(28, 2) NOT NULL,
    released_cost_amount NUMERIC(28, 2) NOT NULL DEFAULT 0,
    realized_profit_loss NUMERIC(28, 2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    trade_time TIMESTAMPTZ NOT NULL,
    settlement_time TIMESTAMPTZ NOT NULL,
    note VARCHAR(500),
    external_reference VARCHAR(100),
    source VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    replaces_transaction_id BIGINT REFERENCES investment_transactions(id),
    reversed_at TIMESTAMPTZ,
    reversal_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_investment_transactions_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_investment_transactions_user_id_id UNIQUE (user_id, id),
    CONSTRAINT fk_investment_transactions_user_asset FOREIGN KEY (user_id, asset_id)
        REFERENCES assets (user_id, id),
    CONSTRAINT fk_investment_transactions_user_account FOREIGN KEY (user_id, account_id)
        REFERENCES accounts (user_id, id),
    CONSTRAINT ck_investment_transactions_type CHECK (transaction_type IN ('BUY', 'SELL', 'DIVIDEND', 'OPENING_POSITION')),
    CONSTRAINT ck_investment_transactions_status CHECK (status IN ('POSTED', 'REVERSED')),
    CONSTRAINT ck_investment_transactions_source CHECK (source IN ('MANUAL', 'MIGRATION', 'IMPORT')),
    CONSTRAINT ck_investment_transactions_currency_format CHECK (currency ~ '^[A-Z]{3}$' AND currency = 'CNY'),
    CONSTRAINT ck_investment_transactions_quantity_positive CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT ck_investment_transactions_unit_price_positive CHECK (unit_price IS NULL OR unit_price > 0),
    CONSTRAINT ck_investment_transactions_amounts_nonnegative CHECK (
        gross_amount >= 0 AND fee_amount >= 0 AND tax_amount >= 0 AND net_amount >= 0 AND released_cost_amount >= 0
    ),
    CONSTRAINT ck_investment_transactions_settlement_time CHECK (settlement_time >= trade_time),
    CONSTRAINT ck_investment_transactions_note_length CHECK (note IS NULL OR char_length(note) <= 500),
    CONSTRAINT ck_investment_transactions_idempotency_key CHECK (char_length(btrim(idempotency_key)) > 0),
    CONSTRAINT ck_investment_transactions_request_hash CHECK (char_length(btrim(request_hash)) > 0),
    CONSTRAINT ck_investment_transactions_reversal_state CHECK (
        (status = 'POSTED' AND reversed_at IS NULL AND reversal_reason IS NULL)
        OR (status = 'REVERSED' AND reversed_at IS NOT NULL AND char_length(btrim(reversal_reason)) > 0)
    ),
    CONSTRAINT ck_investment_transactions_type_fields CHECK (
        (transaction_type IN ('BUY', 'SELL', 'OPENING_POSITION') AND quantity IS NOT NULL AND unit_price IS NOT NULL)
        OR (transaction_type = 'DIVIDEND' AND quantity IS NULL AND unit_price IS NULL)
    ),
    CONSTRAINT ck_investment_transactions_opening_position_amounts CHECK (
        transaction_type <> 'OPENING_POSITION'
        OR (net_amount = 0 AND fee_amount = 0 AND tax_amount = 0 AND released_cost_amount = 0 AND realized_profit_loss = 0)
    ),
    CONSTRAINT ck_investment_transactions_opening_source CHECK (
        transaction_type <> 'OPENING_POSITION' OR source = 'MIGRATION'
    )
);

ALTER TABLE assets
    ADD CONSTRAINT fk_assets_user_last_transaction FOREIGN KEY (user_id, last_transaction_id)
        REFERENCES investment_transactions (user_id, id);

CREATE INDEX idx_investment_transactions_user_trade_time_desc
    ON investment_transactions (user_id, trade_time DESC, id DESC);
CREATE INDEX idx_investment_transactions_user_asset_trade_time
    ON investment_transactions (user_id, asset_id, trade_time, id);
CREATE INDEX idx_investment_transactions_user_account_trade_time_desc
    ON investment_transactions (user_id, account_id, trade_time DESC);
CREATE INDEX idx_investment_transactions_user_type_trade_time_desc
    ON investment_transactions (user_id, transaction_type, trade_time DESC);
