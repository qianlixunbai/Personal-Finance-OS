ALTER TABLE assets
    ADD COLUMN instrument_id BIGINT,
    ADD CONSTRAINT fk_assets_user_instrument FOREIGN KEY (user_id, instrument_id)
        REFERENCES investment_instruments (user_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_assets_transaction_driven_binding CHECK (
        position_mode <> 'TRANSACTION_DRIVEN'
        OR (account_id IS NOT NULL AND instrument_id IS NOT NULL)
    );

CREATE INDEX idx_assets_user_instrument_id
    ON assets (user_id, instrument_id);

CREATE UNIQUE INDEX uk_assets_transaction_driven_user_account_instrument
    ON assets (user_id, account_id, instrument_id)
    WHERE position_mode = 'TRANSACTION_DRIVEN';
