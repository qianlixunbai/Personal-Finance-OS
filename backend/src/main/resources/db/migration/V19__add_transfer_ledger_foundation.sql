CREATE TABLE transfers (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    from_account_id BIGINT NOT NULL,
    to_account_id BIGINT NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    description VARCHAR(500),
    transacted_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_transfers_owned_from_account FOREIGN KEY (user_id, from_account_id)
        REFERENCES accounts (user_id, id),
    CONSTRAINT fk_transfers_owned_to_account FOREIGN KEY (user_id, to_account_id)
        REFERENCES accounts (user_id, id),
    CONSTRAINT ck_transfers_distinct_accounts CHECK (from_account_id <> to_account_id),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transfers_currency_cny CHECK (currency = 'CNY')
);

CREATE INDEX idx_transfers_user_transacted_at_desc
    ON transfers (user_id, transacted_at DESC, id DESC);
