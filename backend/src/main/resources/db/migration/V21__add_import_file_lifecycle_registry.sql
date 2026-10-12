CREATE TABLE transaction_import_storage_bindings (
    storage_id UUID PRIMARY KEY,
    deployment_id UUID NOT NULL,
    kind VARCHAR(4) NOT NULL CHECK (kind IN ('RAW', 'PLAN')),
    binding_digest CHAR(64) NOT NULL CHECK (binding_digest ~ '^[0-9a-f]{64}$'),
    quarantined BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (deployment_id, kind, binding_digest),
    UNIQUE (storage_id, kind)
);

CREATE TABLE transaction_import_file_objects (
    reference VARCHAR(36) PRIMARY KEY CHECK (reference ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    storage_id UUID NOT NULL,
    kind VARCHAR(4) NOT NULL CHECK (kind IN ('RAW', 'PLAN')),
    owner_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    intended_session_id UUID NOT NULL,
    session_id UUID REFERENCES transaction_import_sessions(id) ON DELETE SET NULL,
    state VARCHAR(8) NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED', 'LIVE', 'RETIRED', 'DELETED')),
    write_complete BOOLEAN NOT NULL DEFAULT FALSE,
    fence_epoch BIGINT NOT NULL DEFAULT 1 CHECK (fence_epoch > 0),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_failure_code VARCHAR(24),
    CHECK (state <> 'LIVE' OR write_complete),
    FOREIGN KEY (storage_id, kind) REFERENCES transaction_import_storage_bindings(storage_id, kind),
    CHECK (state <> 'DELETED' OR deleted_at IS NOT NULL)
);
CREATE INDEX idx_import_file_cleanup ON transaction_import_file_objects(storage_id, state, next_attempt_at, created_at);
CREATE INDEX idx_import_file_session ON transaction_import_file_objects(intended_session_id, reference);

-- Existing Session references are enrolled by the application only after the trusted
-- storage binding and the exact referenced object have been verified. No filesystem
-- inventory, reference clearing, or destructive backfill is performed by Flyway.
