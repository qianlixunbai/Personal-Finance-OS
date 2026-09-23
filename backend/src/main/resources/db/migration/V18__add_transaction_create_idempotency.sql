-- P1-2: optional client-supplied idempotency key for ordinary transaction creation.
--
-- Nullable on purpose: existing rows, Transaction Import rows and callers that do not
-- send an Idempotency-Key header keep working unchanged. Only when a key is supplied
-- does the partial unique index protect against duplicate money facts.
--
-- Deliberately NOT added: any uniqueness over (account, category, type, amount, time).
-- Two genuinely identical expenses on the same day are legal business facts; a
-- business-key unique constraint would wrongly reject them.
ALTER TABLE transactions
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN request_hash CHAR(64);

ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_idempotency_key_canonical CHECK (
        idempotency_key IS NULL
        OR (idempotency_key = btrim(idempotency_key) AND char_length(idempotency_key) BETWEEN 1 AND 100)
    ),
    ADD CONSTRAINT ck_transactions_request_hash_sha256 CHECK (
        request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_transactions_idempotency_pair CHECK (
        (idempotency_key IS NULL) = (request_hash IS NULL)
    );

CREATE UNIQUE INDEX uk_transactions_user_idempotency
    ON transactions (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
