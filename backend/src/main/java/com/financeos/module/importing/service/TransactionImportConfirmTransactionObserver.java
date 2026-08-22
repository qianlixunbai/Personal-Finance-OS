package com.financeos.module.importing.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Observability seam: called only after a successful financial transaction has committed.
 * The default implementation deliberately has no business side effect.
 */
@Component
public class TransactionImportConfirmTransactionObserver {
    public void committed(UUID sessionId, long durationNanos) {
        // Hook for metrics/observability.  Keeping the default no-op preserves transaction semantics.
    }

    /**
     * Observability seam after the Session row lock has been acquired. The default implementation
     * remains a no-op; it never influences validation, persistence, or transaction outcome.
     */
    public void sessionLockAcquired(UUID sessionId) {
        // Hook for lock-wait observability and deterministic PostgreSQL concurrency tests.
    }

    /**
     * Observability seam after all affected Account rows are locked in ascending ID order.
     * It deliberately has no business side effect.
     */
    public void accountLocksAcquired(UUID sessionId) {
        // Hook for deterministic duplicate-evidence serialization tests.
    }
}
