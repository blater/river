package io.riverdb.tx.spi;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.version.VacuumResult;
import io.riverdb.tx.api.version.VersionPointer;
import io.riverdb.tx.api.version.VersionRecord;

/**
 * Storage/recovery port owned by the transaction subsystem. Implementations persist version and
 * status state without exposing pages, frames, trees, codecs, or recovery implementation types.
 */
public interface TransactionStorage {
  /** Copies the borrowed prior-image payload into provider-owned stable storage. */
  StatusCode appendVersion(
      TransactionContext context,
      VersionRecord record,
      VersionPointer result,
      StatusDetail detail);

  /** Returns a borrowed stable-lifetime payload view in the caller-owned record carrier. */
  StatusCode readVersion(
      VersionPointer pointer,
      VersionRecord result,
      StatusDetail detail);

  /**
   * Applies the provider-owned physical rollback action associated with the version. Repeated
   * application is idempotent so crash recovery can resume safely.
   */
  StatusCode applyRollback(
      TransactionContext context,
      VersionPointer pointer,
      StatusDetail detail);

  /**
   * Persists a lifecycle view. Providers accept only ACTIVE -> COMMITTING -> COMMITTED or
   * ACTIVE -> ABORTING -> ABORTED, with INDETERMINATE reachable only from a decision state.
   */
  StatusCode storeRecoveryView(
      RecoveryTransactionView view,
      StatusDetail detail);

  StatusCode lookupOutcome(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long transactionId,
      TransactionOutcome result,
      StatusDetail detail);

  StatusCode lookupRecoveryTransaction(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long transactionId,
      RecoveryTransactionView result,
      StatusDetail detail);

  /** Performs no more than {@code maxRecords} units and reports whether work remains. */
  StatusCode vacuumBefore(
      long visibleCommitSequenceExclusive,
      int maxRecords,
      VacuumResult result,
      StatusDetail detail);
}
