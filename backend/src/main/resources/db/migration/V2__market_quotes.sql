CREATE TABLE market_quotes (
    id BIGSERIAL PRIMARY KEY,
    market VARCHAR(10) NOT NULL,
    symbol VARCHAR(30) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    price NUMERIC(20, 8) NOT NULL,
    quote_time TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    provider VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_market_quotes_market_symbol UNIQUE (market, symbol),
    CONSTRAINT ck_market_quotes_price_positive CHECK (price > 0)
);
