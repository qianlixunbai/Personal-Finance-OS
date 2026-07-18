CREATE TABLE exchange_rates (
    id BIGSERIAL PRIMARY KEY,
    base_currency VARCHAR(3) NOT NULL,
    quote_currency VARCHAR(3) NOT NULL,
    rate NUMERIC(24, 12) NOT NULL,
    rate_time TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    provider VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_exchange_rates_currency_pair UNIQUE (base_currency, quote_currency),
    CONSTRAINT ck_exchange_rates_base_currency_format CHECK (base_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_exchange_rates_quote_currency_format CHECK (quote_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_exchange_rates_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_exchange_rates_distinct_currencies CHECK (base_currency <> quote_currency)
);
