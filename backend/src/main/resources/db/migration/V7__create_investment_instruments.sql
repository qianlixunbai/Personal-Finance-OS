-- Phase 2B-2 deliberately does not infer instruments for existing projections.
-- This preflight must run before every DDL statement in this migration.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM assets WHERE position_mode = 'TRANSACTION_DRIVEN') THEN
        RAISE EXCEPTION
            'V7 cannot bind existing TRANSACTION_DRIVEN assets without an explicit opening migration';
    END IF;
END $$;

CREATE TABLE investment_instruments (
    id             BIGSERIAL NOT NULL,
    user_id        BIGINT NOT NULL,
    symbol         VARCHAR(30) NOT NULL,
    name           VARCHAR(100) NOT NULL,
    market         VARCHAR(20) NOT NULL,
    asset_class    VARCHAR(20) NOT NULL,
    quote_currency VARCHAR(3) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_investment_instruments PRIMARY KEY (id),
    CONSTRAINT fk_investment_instruments_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uk_investment_instruments_user_id UNIQUE (user_id, id),
    CONSTRAINT uk_investment_instruments_user_market_symbol UNIQUE (user_id, market, symbol),
    CONSTRAINT ck_investment_instruments_name_not_blank CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT ck_investment_instruments_symbol_canonical CHECK (symbol ~ '^[A-Z0-9][A-Z0-9./:-]{0,29}$'),
    CONSTRAINT ck_investment_instruments_market CHECK (market IN (
        'US', 'HK', 'CN', 'JP', 'KR', 'CRYPTO', 'OTC', 'FUND', 'OTHER', 'UNKNOWN'
    )),
    CONSTRAINT ck_investment_instruments_asset_class CHECK (asset_class IN (
        'STOCK', 'ETF', 'FUND', 'BOND', 'CRYPTO'
    )),
    CONSTRAINT ck_investment_instruments_quote_currency CHECK (quote_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_investment_instruments_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_investment_instruments_user_status
    ON investment_instruments (user_id, status);
