package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/**
 * Commit frontier and admission state sampled under one transaction-manager barrier.
 * Implementations must be non-blocking and return a non-null status.
 */
public interface TransactionAdmissionSource extends CommitSequenceSource {
  StatusCode transactionAdmissionStatus();
}
