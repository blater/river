package io.riverdb.tx.spi;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/**
 * Recovery-authority capability kept separate from ordinary transaction storage references. The
 * recovery orchestrator receives this port only after it has established authoritative journal
 * evidence for the selected history.
 */
public interface RecoveryTransactionStorage {
  /**
   * Resolves uncertainty to COMMITTED when recovery proves the decision durable, or to ABORTING
   * when stable history proves it absent. ABORTING still requires WAL-driven loser undo and CLRs
   * before ordinary state persistence may record ABORTED. A provisional CSN is then a gap.
   */
  StatusCode resolveIndeterminate(
      RecoveryTransactionView resolvedView,
      StatusDetail detail);

  StatusCode lookupRecoveryTransaction(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long transactionId,
      RecoveryTransactionView result,
      StatusDetail detail);
}
