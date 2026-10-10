-- Flyway executes this PostgreSQL migration in one transaction. Acquire the
-- parent lock first, then the receipt-item lock, at the strongest mode needed
-- by DROP CONSTRAINT (including removal of the parent's FK triggers). This
-- avoids a later lock upgrade and prevents writes during validation/cutover.
-- Locks and all DDL are released/rolled back together if any statement fails.
LOCK TABLE transactions, transaction_import_items IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM transaction_import_items item
        WHERE NOT EXISTS (
            SELECT 1
            FROM transactions transaction
            WHERE transaction.user_id = item.user_id
              AND transaction.id = item.created_transaction_id
        )
    ) THEN
        RAISE EXCEPTION 'cannot decouple transaction import receipt with an invalid historical transaction reference'
            USING ERRCODE = '23503', CONSTRAINT = 'fk_transaction_import_items_owned_transaction';
    END IF;
END;
$$;

CREATE FUNCTION validate_transaction_import_item_transaction_reference()
RETURNS TRIGGER AS $$
BEGIN
    -- The reference must be genuine and owned at INSERT time. Hold the key
    -- share lock until commit so concurrent DELETE cannot invalidate that
    -- evidence before the item commits. Later DELETE may leave the ID as
    -- immutable historical evidence; it must not change the receipt. Qualify
    -- the application's public schema so a temporary table cannot shadow it.
    PERFORM 1
    FROM public.transactions transaction
    WHERE transaction.user_id = NEW.user_id
      AND transaction.id = NEW.created_transaction_id
    FOR KEY SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'transaction import item requires an existing owned transaction'
            USING ERRCODE = '23503',
                  CONSTRAINT = 'fk_transaction_import_items_owned_transaction',
                  TABLE = 'transaction_import_items';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transaction_import_items_validate_transaction_reference
    BEFORE INSERT ON transaction_import_items
    FOR EACH ROW EXECUTE FUNCTION validate_transaction_import_item_transaction_reference();

-- Install INSERT validation before removing the ongoing lifecycle dependency.
-- NOT NULL, the owned Batch FK, and every immutable receipt trigger stay intact.
ALTER TABLE transaction_import_items
    DROP CONSTRAINT fk_transaction_import_items_owned_transaction;
