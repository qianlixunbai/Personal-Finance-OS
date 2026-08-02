-- Phase 2B-5B-1: append-only replacement command envelope and canonical replay identity.
-- This migration intentionally provides storage and replay invariants only.  The replacement
-- write path, account mutation, and projection update remain outside this phase.
DO $$
DECLARE
    required_constraint TEXT;
    required_index TEXT;
    required_column RECORD;
    actual_definition TEXT;
    expected_definition TEXT;
    actual_type TEXT;
    actual_not_null BOOLEAN;
    actual_default TEXT;
    expected_referenced_table TEXT;
    expected_local_columns TEXT[];
    expected_referenced_columns TEXT[];
    actual_referenced_table TEXT;
    actual_referenced_schema TEXT;
    actual_local_columns TEXT[];
    actual_referenced_columns TEXT[];
    actual_update_action CHAR(1);
    actual_delete_action CHAR(1);
    actual_match_type CHAR(1);
    actual_deferrable BOOLEAN;
    actual_initially_deferred BOOLEAN;
    actual_validated BOOLEAN;
BEGIN
    -- Generate PostgreSQL's own canonical rendering of every V12 object, then
    -- compare that full rendering.  This rejects same-name semantic drift before
    -- the receipt constraints are dropped below.
    -- This is deliberately explicit rather than merely checking column names:
    -- V13 changes receipt semantics and must never reinterpret a V12 fact whose
    -- storage definition has drifted under a familiar name.
    CREATE TEMP TABLE v13_expected_v12_column_definitions (
        column_name TEXT PRIMARY KEY,
        expected_type TEXT NOT NULL,
        expected_not_null BOOLEAN NOT NULL,
        expected_default TEXT
    ) ON COMMIT DROP;
    INSERT INTO v13_expected_v12_column_definitions
        (column_name, expected_type, expected_not_null, expected_default)
    VALUES
        ('id', 'bigint', true, 'nextval(''investment_transactions_id_seq''::regclass)'),
        ('user_id', 'bigint', true, NULL),
        ('asset_id', 'bigint', true, NULL),
        ('account_id', 'bigint', true, NULL),
        ('transaction_type', 'character varying(20)', true, NULL),
        ('status', 'character varying(20)', true, '''POSTED''::character varying'),
        ('quantity', 'numeric(28,8)', false, NULL),
        ('unit_price', 'numeric(28,8)', false, NULL),
        ('gross_amount', 'numeric(28,2)', true, NULL),
        ('fee_amount', 'numeric(28,2)', true, '0'),
        ('tax_amount', 'numeric(28,2)', true, '0'),
        ('net_amount', 'numeric(28,2)', true, NULL),
        ('released_cost_amount', 'numeric(28,2)', true, '0'),
        ('realized_profit_loss', 'numeric(28,2)', true, '0'),
        ('currency', 'character varying(3)', true, '''CNY''::character varying'),
        ('trade_time', 'timestamp with time zone', true, NULL),
        ('settlement_time', 'timestamp with time zone', true, NULL),
        ('note', 'character varying(500)', false, NULL),
        ('external_reference', 'character varying(100)', false, NULL),
        ('source', 'character varying(20)', true, NULL),
        ('idempotency_key', 'character varying(100)', true, NULL),
        ('request_hash', 'character varying(128)', true, NULL),
        ('replaces_transaction_id', 'bigint', false, NULL),
        ('reversed_at', 'timestamp with time zone', false, NULL),
        ('reversal_reason', 'character varying(500)', false, NULL),
        ('created_at', 'timestamp with time zone', true, 'CURRENT_TIMESTAMP'),
        ('updated_at', 'timestamp with time zone', true, 'CURRENT_TIMESTAMP'),
        ('account_balance_after', 'numeric(18,2)', false, NULL),
        ('position_quantity_after', 'numeric(28,8)', false, NULL),
        ('position_avg_cost_after', 'numeric(28,8)', false, NULL),
        ('position_total_cost_after', 'numeric(28,2)', false, NULL),
        ('position_realized_profit_loss_after', 'numeric(28,2)', false, NULL),
        ('position_status_after', 'character varying(20)', false, NULL),
        ('projection_version_after', 'integer', false, NULL),
        ('original_transaction_id', 'bigint', false, NULL),
        ('correction_reason', 'character varying(500)', false, NULL),
        ('cash_delta', 'numeric(28,2)', false, NULL);

    FOR required_column IN SELECT * FROM v13_expected_v12_column_definitions ORDER BY column_name LOOP
        SELECT format_type(attribute_row.atttypid, attribute_row.atttypmod),
               attribute_row.attnotnull,
               pg_get_expr(default_row.adbin, default_row.adrelid)
          INTO actual_type, actual_not_null, actual_default
          FROM pg_attribute attribute_row
          LEFT JOIN pg_attrdef default_row
            ON default_row.adrelid = attribute_row.attrelid
           AND default_row.adnum = attribute_row.attnum
         WHERE attribute_row.attrelid = 'investment_transactions'::regclass
           AND attribute_row.attname = required_column.column_name
           AND attribute_row.attnum > 0
           AND NOT attribute_row.attisdropped;
        IF actual_type IS NULL
           OR actual_type <> required_column.expected_type
           OR actual_not_null IS DISTINCT FROM required_column.expected_not_null
           OR actual_default IS DISTINCT FROM required_column.expected_default THEN
            RAISE EXCEPTION 'expected V12 column definition is incompatible: %', required_column.column_name;
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM pg_attribute
        WHERE attrelid = 'investment_transactions'::regclass
          AND attnum > 0 AND NOT attisdropped) <> 37 THEN
        RAISE EXCEPTION 'expected V12 investment transaction column count is incompatible';
    END IF;

    CREATE TEMP TABLE v13_expected_v12_investment_transactions (
        id BIGINT, user_id BIGINT, account_id BIGINT, asset_id BIGINT,
        transaction_type VARCHAR(20), status VARCHAR(20), source VARCHAR(20),
        quantity NUMERIC(28, 8), unit_price NUMERIC(28, 8),
        gross_amount NUMERIC(28,2), fee_amount NUMERIC(28,2), tax_amount NUMERIC(28,2), net_amount NUMERIC(28,2),
        released_cost_amount NUMERIC(28,2), realized_profit_loss NUMERIC(28,2), currency VARCHAR(3),
        trade_time TIMESTAMPTZ, settlement_time TIMESTAMPTZ, note VARCHAR(500), idempotency_key VARCHAR(100), request_hash VARCHAR(128),
        replaces_transaction_id BIGINT, reversed_at TIMESTAMPTZ, reversal_reason VARCHAR(500),
        original_transaction_id BIGINT,
        correction_reason VARCHAR(500), cash_delta NUMERIC(28, 2),
        account_balance_after NUMERIC(18, 2), position_quantity_after NUMERIC(28, 8),
        position_avg_cost_after NUMERIC(28, 8), position_total_cost_after NUMERIC(28, 2),
        position_realized_profit_loss_after NUMERIC(28, 2), position_status_after VARCHAR(20),
        projection_version_after INTEGER,
        CONSTRAINT uk_investment_transactions_user_account_asset_id UNIQUE (user_id, account_id, asset_id, id),
        CONSTRAINT ck_investment_transactions_type CHECK (
            transaction_type IN ('BUY', 'SELL', 'DIVIDEND', 'OPENING_POSITION', 'REVERSAL')),
        CONSTRAINT ck_investment_transactions_status CHECK (status = 'POSTED'),
        CONSTRAINT ck_investment_transactions_source CHECK (source IN ('MANUAL', 'MIGRATION', 'IMPORT', 'CORRECTION')),
        CONSTRAINT ck_investment_transactions_legacy_correction_fields_empty CHECK (
            replaces_transaction_id IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL),
        CONSTRAINT ck_investment_transactions_type_fields CHECK (
            (transaction_type IN ('BUY', 'SELL', 'OPENING_POSITION') AND quantity IS NOT NULL AND unit_price IS NOT NULL)
            OR (transaction_type = 'DIVIDEND' AND quantity IS NULL AND unit_price IS NULL)
            OR (transaction_type = 'REVERSAL' AND ((quantity IS NULL AND unit_price IS NULL) OR (quantity IS NOT NULL AND unit_price IS NOT NULL)))),
        CONSTRAINT ck_investment_transactions_correction_fields CHECK (
            (transaction_type = 'REVERSAL' AND source = 'CORRECTION' AND original_transaction_id IS NOT NULL
                AND cash_delta IS NOT NULL AND correction_reason = btrim(correction_reason)
                AND char_length(correction_reason) BETWEEN 1 AND 500)
            OR (transaction_type <> 'REVERSAL' AND source <> 'CORRECTION' AND original_transaction_id IS NULL
                AND correction_reason IS NULL AND cash_delta IS NULL)),
        CONSTRAINT ck_investment_transactions_receipt CHECK (
            (transaction_type = 'OPENING_POSITION' AND account_balance_after IS NULL AND position_quantity_after IS NULL
                AND position_avg_cost_after IS NULL AND position_total_cost_after IS NULL
                AND position_realized_profit_loss_after IS NULL AND position_status_after IS NULL
                AND projection_version_after IS NULL)
            OR (transaction_type IN ('BUY', 'SELL', 'DIVIDEND', 'REVERSAL') AND account_balance_after IS NOT NULL
                AND position_quantity_after IS NOT NULL AND position_avg_cost_after IS NOT NULL
                AND position_total_cost_after IS NOT NULL AND position_realized_profit_loss_after IS NOT NULL
                AND position_status_after IN ('OPEN', 'CLOSED') AND projection_version_after > 0
                AND ((position_quantity_after = 0 AND position_total_cost_after = 0 AND position_avg_cost_after = 0
                        AND position_status_after = 'CLOSED')
                    OR (position_quantity_after > 0 AND position_total_cost_after > 0 AND position_avg_cost_after > 0
                        AND position_status_after = 'OPEN'))))
    ) ON COMMIT DROP;
    ALTER TABLE v13_expected_v12_investment_transactions
        ADD CONSTRAINT investment_transactions_pkey PRIMARY KEY (id),
        ADD CONSTRAINT uk_investment_transactions_user_idempotency UNIQUE (user_id, idempotency_key),
        ADD CONSTRAINT uk_investment_transactions_user_id_id UNIQUE (user_id, id),
        ADD CONSTRAINT uk_investment_transactions_user_asset_id UNIQUE (user_id, asset_id, id),
        ADD CONSTRAINT ck_investment_transactions_currency_format CHECK (currency ~ '^[A-Z]{3}$' AND currency = 'CNY'),
        ADD CONSTRAINT ck_investment_transactions_quantity_positive CHECK (quantity IS NULL OR quantity > 0),
        ADD CONSTRAINT ck_investment_transactions_unit_price_positive CHECK (unit_price IS NULL OR unit_price > 0),
        ADD CONSTRAINT ck_investment_transactions_amounts_nonnegative CHECK (gross_amount >= 0 AND fee_amount >= 0 AND tax_amount >= 0 AND net_amount >= 0 AND released_cost_amount >= 0),
        ADD CONSTRAINT ck_investment_transactions_settlement_time CHECK (settlement_time >= trade_time),
        ADD CONSTRAINT ck_investment_transactions_note_length CHECK (note IS NULL OR char_length(note) <= 500),
        ADD CONSTRAINT ck_investment_transactions_idempotency_key CHECK (char_length(btrim(idempotency_key)) > 0),
        ADD CONSTRAINT ck_investment_transactions_request_hash CHECK (char_length(btrim(request_hash)) > 0),
        ADD CONSTRAINT ck_investment_transactions_opening_position_amounts CHECK (transaction_type <> 'OPENING_POSITION' OR (net_amount = 0 AND fee_amount = 0 AND tax_amount = 0 AND released_cost_amount = 0 AND realized_profit_loss = 0)),
        ADD CONSTRAINT ck_investment_transactions_opening_source CHECK (transaction_type <> 'OPENING_POSITION' OR source = 'MIGRATION'),
        ADD CONSTRAINT ck_investment_transactions_buy_amounts CHECK (transaction_type <> 'BUY' OR (gross_amount > 0 AND net_amount = gross_amount + fee_amount + tax_amount AND released_cost_amount = 0 AND realized_profit_loss = 0)),
        ADD CONSTRAINT ck_investment_transactions_sell_amounts CHECK (transaction_type <> 'SELL' OR (gross_amount > 0 AND net_amount = gross_amount - fee_amount - tax_amount AND net_amount >= 0 AND released_cost_amount > 0 AND realized_profit_loss = net_amount - released_cost_amount)),
        ADD CONSTRAINT ck_investment_transactions_dividend_amounts CHECK (transaction_type <> 'DIVIDEND' OR (gross_amount > 0 AND net_amount = gross_amount - fee_amount - tax_amount AND net_amount >= 0 AND released_cost_amount = 0 AND realized_profit_loss = 0)),
        ADD CONSTRAINT ck_investment_transactions_opening_position_amounts_v5 CHECK (transaction_type <> 'OPENING_POSITION' OR (gross_amount > 0 AND fee_amount = 0 AND tax_amount = 0 AND net_amount = 0 AND released_cost_amount = 0 AND realized_profit_loss = 0)),
        ADD CONSTRAINT ck_investment_transactions_replacement_not_self CHECK (replaces_transaction_id IS NULL OR replaces_transaction_id <> id),
        ADD CONSTRAINT ck_investment_transactions_request_hash_sha256 CHECK (request_hash ~ '^[0-9a-f]{64}$'),
        ADD CONSTRAINT ck_investment_transactions_idempotency_key_canonical CHECK (idempotency_key = btrim(idempotency_key) AND char_length(idempotency_key) BETWEEN 1 AND 100);
    CREATE UNIQUE INDEX v13_expected_v12_reversal_original ON v13_expected_v12_investment_transactions (user_id, original_transaction_id)
        WHERE transaction_type = 'REVERSAL';
    CREATE INDEX idx_investment_transactions_user_trade_time_desc
        ON v13_expected_v12_investment_transactions (user_id, trade_time DESC, id DESC);
    CREATE INDEX idx_investment_transactions_user_asset_trade_time
        ON v13_expected_v12_investment_transactions (user_id, asset_id, trade_time, id);
    CREATE INDEX idx_investment_transactions_user_account_trade_time_desc
        ON v13_expected_v12_investment_transactions (user_id, account_id, trade_time DESC);
    CREATE INDEX idx_investment_transactions_user_type_trade_time_desc
        ON v13_expected_v12_investment_transactions (user_id, transaction_type, trade_time DESC);
    CREATE UNIQUE INDEX uk_investment_transactions_posted_opening_asset
        ON v13_expected_v12_investment_transactions (user_id, asset_id)
        WHERE transaction_type = 'OPENING_POSITION' AND status = 'POSTED';

    FOREACH required_constraint IN ARRAY ARRAY[
        'ck_investment_transactions_type', 'ck_investment_transactions_status',
        'ck_investment_transactions_source', 'ck_investment_transactions_legacy_correction_fields_empty',
        'ck_investment_transactions_type_fields', 'ck_investment_transactions_receipt',
        'ck_investment_transactions_correction_fields',
        'uk_investment_transactions_user_account_asset_id',
        'investment_transactions_pkey', 'uk_investment_transactions_user_idempotency', 'uk_investment_transactions_user_id_id',
        'uk_investment_transactions_user_asset_id',
        'ck_investment_transactions_currency_format', 'ck_investment_transactions_quantity_positive', 'ck_investment_transactions_unit_price_positive',
        'ck_investment_transactions_amounts_nonnegative', 'ck_investment_transactions_settlement_time', 'ck_investment_transactions_note_length',
        'ck_investment_transactions_idempotency_key', 'ck_investment_transactions_request_hash', 'ck_investment_transactions_opening_position_amounts',
        'ck_investment_transactions_opening_source', 'ck_investment_transactions_buy_amounts', 'ck_investment_transactions_sell_amounts',
        'ck_investment_transactions_dividend_amounts', 'ck_investment_transactions_opening_position_amounts_v5',
        'ck_investment_transactions_replacement_not_self', 'ck_investment_transactions_request_hash_sha256',
        'ck_investment_transactions_idempotency_key_canonical'
    ] LOOP
        SELECT pg_get_constraintdef(oid) INTO actual_definition FROM pg_constraint
        WHERE conrelid = 'investment_transactions'::regclass AND conname = required_constraint;
        SELECT pg_get_constraintdef(oid) INTO expected_definition FROM pg_constraint
        WHERE conrelid = 'v13_expected_v12_investment_transactions'::regclass AND conname = required_constraint;
        IF actual_definition IS NULL THEN
            IF required_constraint = 'ck_investment_transactions_receipt' THEN
                RAISE EXCEPTION 'expected V12 receipt constraint is missing';
            END IF;
            RAISE EXCEPTION 'expected V12 constraint % is missing', required_constraint;
        END IF;
        IF regexp_replace(regexp_replace(lower(replace(actual_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') <>
           regexp_replace(regexp_replace(lower(replace(replace(expected_definition,
                'v13_expected_v12_investment_transactions', 'investment_transactions'), 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') THEN
            IF required_constraint = 'ck_investment_transactions_receipt' THEN
                RAISE EXCEPTION 'expected V12 receipt constraint definition is incompatible';
            ELSIF required_constraint = 'ck_investment_transactions_correction_fields' THEN
                RAISE EXCEPTION 'expected V12 correction-fields constraint definition is incompatible';
            END IF;
            RAISE EXCEPTION 'expected V12 % constraint definition is incompatible', required_constraint;
        END IF;
    END LOOP;

    -- PostgreSQL does not allow a temporary table to reference permanent tables.
    -- Verify the V12 foreign keys from catalog column identities instead of trying
    -- to recreate them on the temporary expected-definition table above.
    FOR required_constraint, expected_referenced_table, expected_local_columns, expected_referenced_columns IN
        SELECT * FROM (VALUES
            ('fk_investment_transactions_original_binding', 'investment_transactions',
                ARRAY['user_id', 'account_id', 'asset_id', 'original_transaction_id'], ARRAY['user_id', 'account_id', 'asset_id', 'id']),
            ('fk_investment_transactions_user_asset', 'assets',
                ARRAY['user_id', 'asset_id'], ARRAY['user_id', 'id']),
            ('fk_investment_transactions_user_account', 'accounts',
                ARRAY['user_id', 'account_id'], ARRAY['user_id', 'id']),
            ('fk_investment_transactions_user_asset_account', 'assets',
                ARRAY['user_id', 'asset_id', 'account_id'], ARRAY['user_id', 'id', 'account_id']),
            ('fk_investment_transactions_replacement_user_asset', 'investment_transactions',
                ARRAY['user_id', 'asset_id', 'replaces_transaction_id'], ARRAY['user_id', 'asset_id', 'id'])
        ) AS expected(name, referenced_table, local_columns, referenced_columns)
    LOOP
        SELECT target_table.relname,
               target_schema.nspname,
               ARRAY(SELECT local_attribute.attname
                       FROM unnest(constraint_row.conkey) WITH ORDINALITY AS local_key(attnum, position)
                       JOIN pg_attribute local_attribute
                         ON local_attribute.attrelid = constraint_row.conrelid
                        AND local_attribute.attnum = local_key.attnum
                       ORDER BY local_key.position),
               ARRAY(SELECT referenced_attribute.attname
                       FROM unnest(constraint_row.confkey) WITH ORDINALITY AS referenced_key(attnum, position)
                       JOIN pg_attribute referenced_attribute
                         ON referenced_attribute.attrelid = constraint_row.confrelid
                        AND referenced_attribute.attnum = referenced_key.attnum
                       ORDER BY referenced_key.position),
               constraint_row.confupdtype,
               constraint_row.confdeltype,
               constraint_row.confmatchtype,
               constraint_row.condeferrable,
               constraint_row.condeferred,
               constraint_row.convalidated
          INTO actual_referenced_table, actual_referenced_schema, actual_local_columns, actual_referenced_columns,
               actual_update_action, actual_delete_action, actual_match_type, actual_deferrable,
               actual_initially_deferred, actual_validated
          FROM pg_constraint constraint_row
          JOIN pg_class target_table ON target_table.oid = constraint_row.confrelid
          JOIN pg_namespace target_schema ON target_schema.oid = target_table.relnamespace
         WHERE constraint_row.conrelid = 'investment_transactions'::regclass
           AND constraint_row.conname = required_constraint
           AND constraint_row.contype = 'f';
        IF actual_local_columns IS NULL THEN
            RAISE EXCEPTION 'expected V12 constraint % is missing', required_constraint;
        END IF;
        IF actual_referenced_schema IS DISTINCT FROM 'public'
           OR actual_referenced_table IS DISTINCT FROM expected_referenced_table
           OR actual_local_columns IS DISTINCT FROM expected_local_columns
           OR actual_referenced_columns IS DISTINCT FROM expected_referenced_columns
           OR actual_update_action IS DISTINCT FROM 'a'
           OR actual_delete_action IS DISTINCT FROM 'a'
           OR actual_match_type IS DISTINCT FROM 's'
           OR actual_deferrable IS DISTINCT FROM FALSE
           OR actual_initially_deferred IS DISTINCT FROM FALSE
           OR actual_validated IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'expected V12 % constraint definition is incompatible', required_constraint;
        END IF;
    END LOOP;

    FOREACH required_index IN ARRAY ARRAY[
        'idx_investment_transactions_user_trade_time_desc',
        'idx_investment_transactions_user_asset_trade_time',
        'idx_investment_transactions_user_account_trade_time_desc',
        'idx_investment_transactions_user_type_trade_time_desc',
        'uk_investment_transactions_posted_opening_asset',
        'uk_investment_transactions_reversal_original'
    ] LOOP
        SELECT pg_get_indexdef(to_regclass('public.' || required_index)) INTO actual_definition;
        SELECT pg_get_indexdef(index_row.indexrelid) INTO expected_definition
        FROM pg_index index_row
        JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
        WHERE index_row.indrelid = 'v13_expected_v12_investment_transactions'::regclass
          AND index_class.relname = CASE required_index
              WHEN 'uk_investment_transactions_reversal_original' THEN 'v13_expected_v12_reversal_original'
              ELSE required_index
          END;
        IF actual_definition IS NULL OR expected_definition IS NULL THEN
            RAISE EXCEPTION 'expected V12 index % is missing', required_index;
        END IF;
        expected_definition := regexp_replace(expected_definition,
            '(pg_temp(_[0-9]+)?\.)?v13_expected_v12_investment_transactions', 'investment_transactions', 'g');
        expected_definition := replace(expected_definition, 'v13_expected_v12_reversal_original',
            'uk_investment_transactions_reversal_original');
        IF regexp_replace(regexp_replace(lower(replace(actual_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') <>
           regexp_replace(regexp_replace(lower(replace(expected_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') THEN
            RAISE EXCEPTION 'expected V12 index definition is incompatible: %', required_index;
        END IF;
    END LOOP;

    -- pg_get_functiondef adds server-specific formatting, so create canonical
    -- reference functions and compare its normalized full output, not fragments.
    EXECUTE $sql$
        CREATE FUNCTION v13_expected_validate_append_only_investment_reversal() RETURNS TRIGGER LANGUAGE plpgsql AS $body$
        DECLARE original_row investment_transactions%ROWTYPE; expected_cash_delta NUMERIC(28, 2); BEGIN
        IF NEW.transaction_type <> 'REVERSAL' THEN RETURN NEW; END IF;
        IF NEW.original_transaction_id = NEW.id THEN RAISE EXCEPTION 'investment reversal cannot reference itself' USING ERRCODE = '23514'; END IF;
        SELECT * INTO original_row FROM investment_transactions WHERE id = NEW.original_transaction_id AND user_id = NEW.user_id AND account_id = NEW.account_id AND asset_id = NEW.asset_id;
        IF NOT FOUND OR original_row.transaction_type NOT IN ('BUY', 'SELL', 'DIVIDEND') OR original_row.status <> 'POSTED' OR original_row.original_transaction_id IS NOT NULL OR original_row.replaces_transaction_id IS NOT NULL OR original_row.reversed_at IS NOT NULL OR original_row.reversal_reason IS NOT NULL THEN RAISE EXCEPTION 'investment reversal original is invalid' USING ERRCODE = '23514'; END IF;
        expected_cash_delta := CASE original_row.transaction_type WHEN 'BUY' THEN original_row.net_amount ELSE original_row.net_amount * -1 END;
        IF NEW.cash_delta IS DISTINCT FROM expected_cash_delta OR NEW.gross_amount IS DISTINCT FROM original_row.gross_amount OR NEW.fee_amount IS DISTINCT FROM original_row.fee_amount OR NEW.tax_amount IS DISTINCT FROM original_row.tax_amount OR NEW.net_amount IS DISTINCT FROM original_row.net_amount OR NEW.currency IS DISTINCT FROM original_row.currency OR NEW.released_cost_amount <> 0 OR NEW.realized_profit_loss <> 0 THEN RAISE EXCEPTION 'investment reversal audit values are invalid' USING ERRCODE = '23514'; END IF;
        IF original_row.transaction_type = 'DIVIDEND' THEN IF NEW.quantity IS NOT NULL OR NEW.unit_price IS NOT NULL THEN RAISE EXCEPTION 'dividend reversal quantity and unit price must be null' USING ERRCODE = '23514'; END IF; ELSIF NEW.quantity IS DISTINCT FROM original_row.quantity OR NEW.unit_price IS DISTINCT FROM original_row.unit_price THEN RAISE EXCEPTION 'trade reversal quantity or unit price is invalid' USING ERRCODE = '23514'; END IF;
        RETURN NEW; END;
        $body$
    $sql$;
    EXECUTE $sql$
        CREATE FUNCTION v13_expected_prevent_investment_transaction_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $body$
        BEGIN RAISE EXCEPTION 'investment transaction facts are immutable' USING ERRCODE = '23514'; END;
        $body$
    $sql$;
    SELECT pg_get_functiondef(to_regprocedure('validate_append_only_investment_reversal()')) INTO actual_definition;
    SELECT replace(pg_get_functiondef('v13_expected_validate_append_only_investment_reversal()'::regprocedure),
                   'v13_expected_validate_append_only_investment_reversal', 'validate_append_only_investment_reversal') INTO expected_definition;
    IF actual_definition IS NULL OR regexp_replace(regexp_replace(lower(replace(actual_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') <> regexp_replace(regexp_replace(lower(replace(expected_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') THEN
        RAISE EXCEPTION 'expected V12 reversal audit function definition is incompatible';
    END IF;
    SELECT pg_get_functiondef(to_regprocedure('prevent_investment_transaction_mutation()')) INTO actual_definition;
    SELECT replace(pg_get_functiondef('v13_expected_prevent_investment_transaction_mutation()'::regprocedure),
                   'v13_expected_prevent_investment_transaction_mutation', 'prevent_investment_transaction_mutation') INTO expected_definition;
    IF actual_definition IS NULL OR regexp_replace(regexp_replace(lower(replace(actual_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') <> regexp_replace(regexp_replace(lower(replace(expected_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') THEN
        RAISE EXCEPTION 'expected V12 immutable fact function definition is incompatible';
    END IF;
    DROP FUNCTION v13_expected_validate_append_only_investment_reversal();
    DROP FUNCTION v13_expected_prevent_investment_transaction_mutation();

    CREATE TEMP TABLE v13_expected_v12_trigger_target (id BIGINT) ON COMMIT DROP;
    CREATE TRIGGER v13_expected_v12_reversal_trigger BEFORE INSERT ON v13_expected_v12_trigger_target
        FOR EACH ROW EXECUTE FUNCTION validate_append_only_investment_reversal();
    CREATE TRIGGER v13_expected_v12_immutable_trigger BEFORE UPDATE OR DELETE ON v13_expected_v12_trigger_target
        FOR EACH ROW EXECUTE FUNCTION prevent_investment_transaction_mutation();
    SELECT pg_get_triggerdef(oid) INTO actual_definition FROM pg_trigger
    WHERE tgrelid = 'investment_transactions'::regclass AND tgname = 'trg_validate_append_only_investment_reversal' AND NOT tgisinternal;
    SELECT replace(replace(pg_get_triggerdef(oid), 'v13_expected_v12_reversal_trigger', 'trg_validate_append_only_investment_reversal'),
                   'v13_expected_v12_trigger_target', 'investment_transactions') INTO expected_definition FROM pg_trigger
    WHERE tgrelid = 'v13_expected_v12_trigger_target'::regclass AND tgname = 'v13_expected_v12_reversal_trigger';
    IF actual_definition IS NULL OR regexp_replace(regexp_replace(lower(replace(actual_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') <> regexp_replace(regexp_replace(lower(replace(expected_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') THEN
        RAISE EXCEPTION 'expected V12 reversal audit trigger definition is incompatible';
    END IF;
    SELECT pg_get_triggerdef(oid) INTO actual_definition FROM pg_trigger
    WHERE tgrelid = 'investment_transactions'::regclass AND tgname = 'trg_prevent_investment_transaction_mutation' AND NOT tgisinternal;
    SELECT replace(replace(pg_get_triggerdef(oid), 'v13_expected_v12_immutable_trigger', 'trg_prevent_investment_transaction_mutation'),
                   'v13_expected_v12_trigger_target', 'investment_transactions') INTO expected_definition FROM pg_trigger
    WHERE tgrelid = 'v13_expected_v12_trigger_target'::regclass AND tgname = 'v13_expected_v12_immutable_trigger';
    IF actual_definition IS NULL OR regexp_replace(regexp_replace(lower(replace(actual_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') <> regexp_replace(regexp_replace(lower(replace(expected_definition, 'public.', '')), 'pg_temp(_[0-9]+)?\.', '', 'g'), '\s+', '', 'g') THEN
        RAISE EXCEPTION 'expected V12 immutable fact trigger definition is incompatible';
    END IF;
END $$;

CREATE TABLE investment_transaction_corrections (
    id BIGSERIAL PRIMARY KEY,
    correction_group_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    instrument_id BIGINT NOT NULL,
    original_transaction_id BIGINT NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    correction_kind VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    correction_reason VARCHAR(500) NOT NULL,
    reversal_transaction_id BIGINT NOT NULL,
    replacement_transaction_id BIGINT NOT NULL,
    reversal_cash_delta NUMERIC(28, 2) NOT NULL,
    replacement_cash_delta NUMERIC(28, 2) NOT NULL,
    command_cash_delta NUMERIC(28, 2) NOT NULL,
    balance_after NUMERIC(18, 2) NOT NULL,
    position_quantity_after NUMERIC(28, 8) NOT NULL,
    position_avg_cost_after NUMERIC(28, 8) NOT NULL,
    position_total_cost_after NUMERIC(28, 2) NOT NULL,
    position_realized_profit_loss_after NUMERIC(28, 2) NOT NULL,
    position_status_after VARCHAR(20) NOT NULL,
    projection_version INTEGER NOT NULL,
    last_transaction_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_investment_transaction_corrections_group UNIQUE (correction_group_id),
    CONSTRAINT uk_investment_transaction_corrections_user_group UNIQUE (user_id, correction_group_id),
    CONSTRAINT uk_investment_transaction_corrections_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT uk_investment_transaction_corrections_user_original UNIQUE (user_id, original_transaction_id),
    CONSTRAINT ck_investment_transaction_corrections_kind CHECK (correction_kind = 'REPLACEMENT'),
    CONSTRAINT ck_investment_transaction_corrections_type CHECK (transaction_type IN ('BUY', 'SELL', 'DIVIDEND')),
    CONSTRAINT ck_investment_transaction_corrections_key CHECK (
        idempotency_key = btrim(idempotency_key) AND char_length(idempotency_key) BETWEEN 1 AND 100),
    CONSTRAINT ck_investment_transaction_corrections_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_investment_transaction_corrections_reason CHECK (
        correction_reason = btrim(correction_reason) AND char_length(correction_reason) BETWEEN 1 AND 500),
    CONSTRAINT ck_investment_transaction_corrections_cash_delta CHECK (
        command_cash_delta = reversal_cash_delta + replacement_cash_delta),
    CONSTRAINT ck_investment_transaction_corrections_position CHECK (
        projection_version > 0
        AND position_status_after IN ('OPEN', 'CLOSED')
        AND ((position_quantity_after = 0 AND position_total_cost_after = 0
              AND position_avg_cost_after = 0 AND position_status_after = 'CLOSED')
             OR (position_quantity_after > 0 AND position_total_cost_after > 0
                 AND position_avg_cost_after > 0 AND position_status_after = 'OPEN'))),
    CONSTRAINT fk_investment_transaction_corrections_original_binding
        FOREIGN KEY (user_id, account_id, asset_id, original_transaction_id)
        REFERENCES investment_transactions (user_id, account_id, asset_id, id),
    CONSTRAINT fk_investment_transaction_corrections_instrument_binding
        FOREIGN KEY (user_id, instrument_id) REFERENCES investment_instruments (user_id, id)
);

ALTER TABLE investment_transactions
    DROP CONSTRAINT ck_investment_transactions_receipt,
    DROP CONSTRAINT ck_investment_transactions_correction_fields,
    ADD COLUMN correction_group_id UUID,
    ADD COLUMN replay_anchor_transaction_id BIGINT,
    ADD COLUMN replay_sequence SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_investment_transactions_replay_sequence CHECK (replay_sequence >= 0),
    ADD CONSTRAINT ck_investment_transactions_replay_anchor CHECK (
        (replay_anchor_transaction_id IS NULL AND replay_sequence = 0)
        OR (replay_anchor_transaction_id IS NOT NULL AND replay_sequence = 1)),
    ADD CONSTRAINT ck_investment_transactions_correction_fields CHECK (
        (transaction_type = 'REVERSAL'
            AND source = 'CORRECTION'
            AND original_transaction_id IS NOT NULL
            AND cash_delta IS NOT NULL
            AND correction_reason = btrim(correction_reason)
            AND char_length(correction_reason) BETWEEN 1 AND 500)
        OR (transaction_type IN ('BUY', 'SELL', 'DIVIDEND')
            AND source = 'CORRECTION'
            AND correction_group_id IS NOT NULL
            AND original_transaction_id IS NULL
            AND correction_reason IS NULL
            AND cash_delta IS NOT NULL)
        OR (transaction_type <> 'REVERSAL'
            AND source <> 'CORRECTION'
            AND original_transaction_id IS NULL
            AND correction_reason IS NULL
            AND cash_delta IS NULL)),
    ADD CONSTRAINT ck_investment_transactions_correction_replay_metadata CHECK (
        (transaction_type = 'REVERSAL'
            AND replay_anchor_transaction_id IS NULL
            AND replay_sequence = 0)
        OR (transaction_type IN ('BUY', 'SELL', 'DIVIDEND')
            AND source = 'CORRECTION'
            AND correction_group_id IS NOT NULL
            AND replay_anchor_transaction_id IS NOT NULL
            AND replay_sequence = 1)
        OR (transaction_type <> 'REVERSAL'
            AND source <> 'CORRECTION'
            AND correction_group_id IS NULL
            AND replay_anchor_transaction_id IS NULL
            AND replay_sequence = 0)),
    ADD CONSTRAINT ck_investment_transactions_receipt
        CHECK (
            (transaction_type = 'OPENING_POSITION'
                AND account_balance_after IS NULL
                AND position_quantity_after IS NULL
                AND position_avg_cost_after IS NULL
                AND position_total_cost_after IS NULL
                AND position_realized_profit_loss_after IS NULL
                AND position_status_after IS NULL
                AND projection_version_after IS NULL)
            OR
            (transaction_type = 'REVERSAL' AND correction_group_id IS NOT NULL
                AND account_balance_after IS NULL
                AND position_quantity_after IS NULL
                AND position_avg_cost_after IS NULL
                AND position_total_cost_after IS NULL
                AND position_realized_profit_loss_after IS NULL
                AND position_status_after IS NULL
                AND projection_version_after IS NULL)
            OR
            ((transaction_type IN ('BUY', 'SELL', 'DIVIDEND')
                OR (transaction_type = 'REVERSAL' AND correction_group_id IS NULL))
                AND account_balance_after IS NOT NULL
                AND position_quantity_after IS NOT NULL
                AND position_avg_cost_after IS NOT NULL
                AND position_total_cost_after IS NOT NULL
                AND position_realized_profit_loss_after IS NOT NULL
                AND position_status_after IN ('OPEN', 'CLOSED')
                AND projection_version_after > 0
                AND ((position_quantity_after = 0 AND position_total_cost_after = 0
                      AND position_avg_cost_after = 0 AND position_status_after = 'CLOSED')
                     OR (position_quantity_after > 0 AND position_total_cost_after > 0
                         AND position_avg_cost_after > 0 AND position_status_after = 'OPEN')))),
    ADD CONSTRAINT uk_investment_transactions_user_group_id UNIQUE (user_id, correction_group_id, id),
    ADD CONSTRAINT fk_investment_transactions_correction_group_command
        FOREIGN KEY (user_id, correction_group_id)
        REFERENCES investment_transaction_corrections (user_id, correction_group_id)
        DEFERRABLE INITIALLY DEFERRED;

-- Retain V12's global reversal-original uniqueness: a fact may be corrected only once,
-- whether it is a standalone reversal or the reversal half of a replacement group.

CREATE UNIQUE INDEX uk_investment_transactions_group_reversal
    ON investment_transactions (correction_group_id)
    WHERE transaction_type = 'REVERSAL' AND correction_group_id IS NOT NULL;

CREATE UNIQUE INDEX uk_investment_transactions_group_replacement
    ON investment_transactions (correction_group_id)
    WHERE transaction_type <> 'REVERSAL' AND correction_group_id IS NOT NULL;

CREATE UNIQUE INDEX uk_investment_transactions_replacement_anchor
    ON investment_transactions (user_id, replay_anchor_transaction_id)
    WHERE transaction_type <> 'REVERSAL' AND correction_group_id IS NOT NULL;

ALTER TABLE investment_transaction_corrections
    ADD CONSTRAINT fk_investment_transaction_corrections_reversal_fact
        FOREIGN KEY (user_id, correction_group_id, reversal_transaction_id)
        REFERENCES investment_transactions (user_id, correction_group_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_investment_transaction_corrections_replacement_fact
        FOREIGN KEY (user_id, correction_group_id, replacement_transaction_id)
        REFERENCES investment_transactions (user_id, correction_group_id, id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE OR REPLACE FUNCTION validate_investment_replacement_fact()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    anchor_row investment_transactions%ROWTYPE;
    reversal_original_group_id UUID;
    expected_cash_delta NUMERIC(28, 2);
BEGIN
    IF NEW.transaction_type = 'REVERSAL' THEN
        IF NEW.replay_anchor_transaction_id IS NOT NULL OR NEW.replay_sequence <> 0 THEN
            RAISE EXCEPTION 'investment reversal cannot carry replay anchor metadata' USING ERRCODE = '23514';
        END IF;
        SELECT correction_group_id INTO reversal_original_group_id
        FROM investment_transactions WHERE id = NEW.original_transaction_id AND user_id = NEW.user_id;
        IF reversal_original_group_id IS NOT NULL THEN
            RAISE EXCEPTION 'investment correction facts cannot be corrected again' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.source <> 'CORRECTION' THEN
        IF NEW.correction_group_id IS NOT NULL OR NEW.replay_anchor_transaction_id IS NOT NULL
           OR NEW.replay_sequence <> 0 THEN
            RAISE EXCEPTION 'ordinary investment fact cannot carry replacement replay metadata' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO anchor_row FROM investment_transactions
    WHERE id = NEW.replay_anchor_transaction_id
      AND user_id = NEW.user_id AND account_id = NEW.account_id AND asset_id = NEW.asset_id;
    IF NOT FOUND OR anchor_row.transaction_type NOT IN ('BUY', 'SELL', 'DIVIDEND')
       OR anchor_row.correction_group_id IS NOT NULL
       OR anchor_row.original_transaction_id IS NOT NULL
       OR anchor_row.status <> 'POSTED' THEN
        RAISE EXCEPTION 'investment replacement anchor is invalid' USING ERRCODE = '23514';
    END IF;
    expected_cash_delta := CASE NEW.transaction_type WHEN 'BUY' THEN NEW.net_amount * -1 ELSE NEW.net_amount END;
    IF NEW.transaction_type <> anchor_row.transaction_type
       OR NEW.currency IS DISTINCT FROM anchor_row.currency
       OR NEW.trade_time IS DISTINCT FROM anchor_row.trade_time
       OR NEW.settlement_time IS DISTINCT FROM anchor_row.settlement_time
       OR NEW.cash_delta IS DISTINCT FROM expected_cash_delta THEN
        RAISE EXCEPTION 'investment replacement fact does not preserve its original binding' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_investment_replacement_fact
BEFORE INSERT ON investment_transactions
FOR EACH ROW EXECUTE FUNCTION validate_investment_replacement_fact();

CREATE OR REPLACE FUNCTION validate_investment_replacement_group_complete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    group_id UUID := NEW.correction_group_id;
    command_row investment_transaction_corrections%ROWTYPE;
    original_row investment_transactions%ROWTYPE;
    reversal_row investment_transactions%ROWTYPE;
    replacement_row investment_transactions%ROWTYPE;
    replacement_count INTEGER;
    replacement_id BIGINT;
    asset_instrument_id BIGINT;
BEGIN
    IF group_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT * INTO command_row FROM investment_transaction_corrections WHERE correction_group_id = group_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'replacement correction group requires its command envelope' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO reversal_row FROM investment_transactions
     WHERE correction_group_id = group_id AND transaction_type = 'REVERSAL';
    SELECT count(*), max(id) INTO replacement_count, replacement_id FROM investment_transactions
     WHERE correction_group_id = group_id AND transaction_type <> 'REVERSAL';
    IF reversal_row.id IS NULL OR replacement_count <> 1 THEN
        RAISE EXCEPTION 'replacement correction group must contain one reversal and one replacement fact' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO replacement_row FROM investment_transactions WHERE id = replacement_id;
    SELECT * INTO original_row FROM investment_transactions
     WHERE id = command_row.original_transaction_id AND user_id = command_row.user_id
       AND account_id = command_row.account_id AND asset_id = command_row.asset_id;
    SELECT instrument_id INTO asset_instrument_id FROM assets
     WHERE id = command_row.asset_id AND user_id = command_row.user_id AND account_id = command_row.account_id;
    IF original_row.id IS NULL OR asset_instrument_id IS DISTINCT FROM command_row.instrument_id
       OR original_row.transaction_type <> command_row.transaction_type
       OR original_row.correction_group_id IS NOT NULL
       OR reversal_row.id IS DISTINCT FROM command_row.reversal_transaction_id
       OR replacement_row.id IS DISTINCT FROM command_row.replacement_transaction_id
       OR reversal_row.original_transaction_id IS DISTINCT FROM original_row.id
       OR replacement_row.replay_anchor_transaction_id IS DISTINCT FROM original_row.id
       OR replacement_row.replay_sequence <> 1
       OR replacement_row.original_transaction_id IS NOT NULL
       OR replacement_row.transaction_type <> original_row.transaction_type
       OR replacement_row.currency IS DISTINCT FROM original_row.currency
       OR replacement_row.trade_time IS DISTINCT FROM original_row.trade_time
       OR replacement_row.settlement_time IS DISTINCT FROM original_row.settlement_time
       OR command_row.correction_reason IS DISTINCT FROM reversal_row.correction_reason
       OR command_row.reversal_cash_delta IS DISTINCT FROM reversal_row.cash_delta
       OR command_row.replacement_cash_delta IS DISTINCT FROM replacement_row.cash_delta
       OR command_row.balance_after IS DISTINCT FROM replacement_row.account_balance_after
       OR command_row.position_quantity_after IS DISTINCT FROM replacement_row.position_quantity_after
       OR command_row.position_avg_cost_after IS DISTINCT FROM replacement_row.position_avg_cost_after
       OR command_row.position_total_cost_after IS DISTINCT FROM replacement_row.position_total_cost_after
       OR command_row.position_realized_profit_loss_after IS DISTINCT FROM replacement_row.position_realized_profit_loss_after
       OR command_row.position_status_after IS DISTINCT FROM replacement_row.position_status_after
       OR command_row.projection_version IS DISTINCT FROM replacement_row.projection_version_after
       OR command_row.last_transaction_id IS DISTINCT FROM replacement_row.id THEN
        RAISE EXCEPTION 'replacement correction group binding is invalid' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_validate_investment_replacement_group_complete
AFTER INSERT ON investment_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_investment_replacement_group_complete();

CREATE CONSTRAINT TRIGGER trg_validate_investment_replacement_command_complete
AFTER INSERT ON investment_transaction_corrections
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_investment_replacement_group_complete();

CREATE OR REPLACE FUNCTION prevent_investment_transaction_correction_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'investment transaction correction commands are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_prevent_investment_transaction_correction_mutation
BEFORE UPDATE OR DELETE ON investment_transaction_corrections
FOR EACH ROW EXECUTE FUNCTION prevent_investment_transaction_correction_mutation();
