-- ====================================================================
-- Personal Finance OS v1.0 — Database Schema
-- PostgreSQL 17 · snake_case · all tables include id/created_at/updated_at
-- ====================================================================

-- 1. Users
CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Accounts
CREATE TABLE IF NOT EXISTS accounts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id),
    name            VARCHAR(100) NOT NULL,
    type            VARCHAR(30)  NOT NULL CHECK (type IN (
                        'CASH','BANK','CREDIT_CARD','PAYMENT_PLATFORM',
                        'BROKERAGE','CRYPTO_WALLET','OTHER'
                    )),
    currency        VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    balance         DECIMAL(18,2) NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_accounts_user_id ON accounts(user_id);

-- 3. Categories
CREATE TABLE IF NOT EXISTS categories (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       REFERENCES users(id),
    name            VARCHAR(50)  NOT NULL,
    type            VARCHAR(10)  NOT NULL CHECK (type IN ('INCOME','EXPENSE')),
    parent_id       BIGINT       REFERENCES categories(id),
    is_system       BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_categories_user_id ON categories(user_id);

-- 4. Transactions
CREATE TABLE IF NOT EXISTS transactions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id),
    account_id      BIGINT       NOT NULL REFERENCES accounts(id),
    category_id     BIGINT       NOT NULL REFERENCES categories(id),
    type            VARCHAR(20)  NOT NULL CHECK (type IN (
                        'INCOME','EXPENSE','TRANSFER','REFUND','ADJUSTMENT'
                    )),
    amount          DECIMAL(18,2) NOT NULL,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    description     VARCHAR(500),
    transacted_at   TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_transactions_user_id      ON transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_account_id   ON transactions(account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_category_id  ON transactions(category_id);
CREATE INDEX IF NOT EXISTS idx_transactions_transacted   ON transactions(transacted_at);
CREATE INDEX IF NOT EXISTS idx_transactions_user_currency_type_time
    ON transactions(user_id, currency, type, transacted_at);

-- 5. Assets (Investment Portfolio)
CREATE TABLE IF NOT EXISTS assets (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id),
    name            VARCHAR(100) NOT NULL,
    symbol          VARCHAR(30),
    type            VARCHAR(20)  NOT NULL CHECK (type IN (
                        'STOCK','ETF','FUND','BOND','GOLD','CRYPTO','CASH','OTHER'
                    )),
    market          VARCHAR(30),
    currency        VARCHAR(3)   NOT NULL DEFAULT 'CNY',
    quantity        DECIMAL(18,8) NOT NULL DEFAULT 0,
    avg_cost        DECIMAL(18,4) NOT NULL DEFAULT 0,
    current_price   DECIMAL(18,4),
    market_value    DECIMAL(18,2),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_assets_user_id ON assets(user_id);

-- 6. Asset Prices (Historical)
CREATE TABLE IF NOT EXISTS asset_prices (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(30)  NOT NULL,
    price           DECIMAL(18,4) NOT NULL,
    price_date      DATE         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(symbol, price_date)
);
CREATE INDEX IF NOT EXISTS idx_asset_prices_symbol ON asset_prices(symbol);
