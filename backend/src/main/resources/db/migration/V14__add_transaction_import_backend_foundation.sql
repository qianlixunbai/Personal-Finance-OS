ALTER TABLE transactions
    ADD CONSTRAINT uk_transactions_user_id UNIQUE (user_id, id);

CREATE TABLE transaction_import_sessions (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    preallocated_batch_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    original_file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(150),
    file_size BIGINT NOT NULL,
    file_digest CHAR(64) NOT NULL,
    temporary_storage_reference VARCHAR(128) NOT NULL,
    mapping_digest CHAR(64),
    options_digest CHAR(64) NOT NULL,
    normalized_rows_digest CHAR(64),
    plan_storage_reference VARCHAR(128),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_transaction_import_sessions_preallocated_batch UNIQUE (preallocated_batch_id),
    CONSTRAINT ck_transaction_import_sessions_status CHECK (status IN (
        'MAPPING_REQUIRED', 'PREVIEW_READY', 'EXPIRED', 'CANCELLED', 'CONSUMED')),
    CONSTRAINT ck_transaction_import_sessions_revision CHECK (revision >= 0),
    CONSTRAINT ck_transaction_import_sessions_file_size CHECK (file_size > 0 AND file_size <= 5242880),
    CONSTRAINT ck_transaction_import_sessions_file_digest CHECK (file_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_sessions_mapping_digest CHECK (mapping_digest IS NULL OR mapping_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_sessions_options_digest CHECK (options_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_sessions_normalized_rows_digest CHECK (normalized_rows_digest IS NULL OR normalized_rows_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_sessions_terminal_times CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND consumed_at IS NULL)
        OR (status IN ('MAPPING_REQUIRED', 'PREVIEW_READY', 'EXPIRED') AND consumed_at IS NULL AND cancelled_at IS NULL))
);
CREATE INDEX idx_transaction_import_sessions_user_expires_at
    ON transaction_import_sessions (user_id, expires_at);
CREATE INDEX idx_transaction_import_sessions_expiry_cleanup
    ON transaction_import_sessions (expires_at)
    WHERE status IN ('MAPPING_REQUIRED', 'PREVIEW_READY', 'EXPIRED');

CREATE TABLE transaction_import_batches (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    original_file_name VARCHAR(255) NOT NULL,
    file_digest CHAR(64) NOT NULL,
    mapping_digest CHAR(64) NOT NULL,
    options_digest CHAR(64) NOT NULL,
    normalized_rows_digest CHAR(64) NOT NULL,
    contract_version VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_rows INTEGER NOT NULL,
    warning_count INTEGER NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_transaction_import_batches_user_id UNIQUE (user_id, id),
    CONSTRAINT uk_transaction_import_batches_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_transaction_import_batches_exact_duplicate UNIQUE (
        user_id, file_digest, mapping_digest, options_digest, normalized_rows_digest),
    CONSTRAINT ck_transaction_import_batches_status CHECK (status = 'CONFIRMED'),
    CONSTRAINT ck_transaction_import_batches_file_digest CHECK (file_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_batches_mapping_digest CHECK (mapping_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_batches_options_digest CHECK (options_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_batches_normalized_rows_digest CHECK (normalized_rows_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_batches_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transaction_import_batches_idempotency_key CHECK (
        idempotency_key = btrim(idempotency_key) AND char_length(idempotency_key) BETWEEN 1 AND 100),
    CONSTRAINT ck_transaction_import_batches_counts CHECK (total_rows > 0 AND warning_count >= 0 AND warning_count <= total_rows)
);
CREATE INDEX idx_transaction_import_batches_user_confirmed_at
    ON transaction_import_batches (user_id, confirmed_at DESC, id DESC);

CREATE TABLE transaction_import_items (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    batch_id UUID NOT NULL,
    source_row_number INTEGER NOT NULL,
    canonical_row_fingerprint CHAR(64) NOT NULL,
    warning_codes VARCHAR(500) NOT NULL DEFAULT '',
    created_transaction_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_transaction_import_items_owned_batch FOREIGN KEY (user_id, batch_id)
        REFERENCES transaction_import_batches (user_id, id),
    CONSTRAINT fk_transaction_import_items_owned_transaction FOREIGN KEY (user_id, created_transaction_id)
        REFERENCES transactions (user_id, id),
    CONSTRAINT uk_transaction_import_items_batch_row UNIQUE (batch_id, source_row_number),
    CONSTRAINT ck_transaction_import_items_source_row_number CHECK (source_row_number > 0),
    CONSTRAINT ck_transaction_import_items_fingerprint CHECK (canonical_row_fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_transaction_import_items_user_batch
    ON transaction_import_items (user_id, batch_id);
