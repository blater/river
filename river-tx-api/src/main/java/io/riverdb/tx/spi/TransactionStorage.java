package io.riverdb.tx.spi;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.version.VersionPointer;
import io.riverdb.tx.api.version.VersionReadResult;
import io.riverdb.tx.api.version.VersionRecord;

/**
 * Ordinary storage port owned by the transaction subsystem. Implementations persist version and
 * status state without exposing pages, frames, trees, codecs, or recovery implementation types.
 */
public interface TransactionStorage {
  /** Copies the borrowed prior-image payload into provider-owned stable storage. */
  StatusCode appendVersion(
      TransactionContext context,
      VersionRecord record,
      VersionPointer result,
      StatusDetail detail);

  /** Copies payload bytes into the configured caller-owned destination. */
  StatusCode readVersion(
      VersionPointer pointer,
      VersionReadResult result,
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
}
