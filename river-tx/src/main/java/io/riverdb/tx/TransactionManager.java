package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Bounded lifecycle manager and commit/snapshot publication barrier. */
public final class TransactionManager {
  private final long databaseHigh;
  private final long databaseLow;
  private final long[] activeTransactionIds;
  private int activeTransactionCount;
  private long nextTransactionId;
  private long nextSnapshotSequence = 1;

  public TransactionManager(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long firstTransactionId,
      int maximumActive) {
    databaseHigh = databaseIncarnationHigh;
    databaseLow = databaseIncarnationLow;
    nextTransactionId = firstTransactionId;
    activeTransactionIds = new long[maximumActive];
  }

  public int maximumActiveTransactions() {
    return activeTransactionIds.length;
  }

  public synchronized int activeTransactionCount() {
    return activeTransactionCount;
  }

  public synchronized StatusCode begin(
      IsolationLevel isolationLevel,
      long visibleCommitSequence,
      Transaction result) {
    if (isolationLevel == null || visibleCommitSequence < 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (result.isActiveHandle()) {
      return StatusCode.CONFLICT;
    }
    if (activeTransactionCount >= activeTransactionIds.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (nextTransactionId <= 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long transactionId = nextTransactionId++;
    if (nextTransactionId <= 0) {
      nextTransactionId = 0;
    }
    StatusCode status = result.claim(this, transactionId, isolationLevel);
    if (!status.isOk()) {
      return status;
    }
    capture(result, visibleCommitSequence);
    activeTransactionIds[activeTransactionCount++] = transactionId;
    return StatusCode.OK;
  }

  public synchronized StatusCode begin(
      IsolationLevel isolationLevel,
      CommitSequenceSource source,
      Transaction result) {
    if (source == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return begin(isolationLevel, source.currentCommitSequence(), result);
  }

  public synchronized StatusCode refreshReadCommitted(
      Transaction transaction,
      long visibleCommitSequence) {
    if (!validActive(transaction)
        || transaction.isolationLevel() != IsolationLevel.READ_COMMITTED
        || visibleCommitSequence < transaction.snapshot().visibleCommitSequence()) {
      return StatusCode.CONFLICT;
    }
    capture(transaction, visibleCommitSequence);
    return StatusCode.OK;
  }

  public synchronized StatusCode refreshReadCommitted(
      Transaction transaction,
      CommitSequenceSource source) {
    if (source == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return refreshReadCommitted(transaction, source.currentCommitSequence());
  }

  /**
   * Holds the publication barrier across durable participant commit and active-set removal, so a
   * new snapshot cannot observe the commit CSN while still classifying its owner as active.
   */
  public synchronized StatusCode commit(
      Transaction transaction,
      TransactionCommitParticipant participant,
      TransactionOutcome result) {
    if (!validActive(transaction) || participant == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    transaction.transition(TransactionState.COMMITTING, 0, false);
    StatusCode status = participant.commit(transaction.transactionId());
    if (!status.isOk()) {
      removeActive(transaction.transactionId());
      if (indeterminate(status)) {
        transaction.transition(TransactionState.INDETERMINATE, 0, true);
      } else {
        transaction.transition(TransactionState.ABORTED, 0, true);
      }
      result.set(
          databaseHigh,
          databaseLow,
          transaction.transactionId(),
          transaction.state(),
          0);
      return status;
    }
    long commitSequence = participant.committedSequence();
    if (commitSequence <= transaction.snapshot().visibleCommitSequence()) {
      removeActive(transaction.transactionId());
      transaction.transition(TransactionState.INDETERMINATE, 0, true);
      result.set(
          databaseHigh,
          databaseLow,
          transaction.transactionId(),
          TransactionState.INDETERMINATE,
          0);
      return StatusCode.INVARIANT_BROKEN;
    }
    removeActive(transaction.transactionId());
    transaction.transition(TransactionState.COMMITTED, commitSequence, true);
    result.set(
        databaseHigh,
        databaseLow,
        transaction.transactionId(),
        TransactionState.COMMITTED,
        commitSequence);
    return StatusCode.OK;
  }

  public synchronized StatusCode abort(Transaction transaction, TransactionOutcome result) {
    if (!validActive(transaction) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    transaction.transition(TransactionState.ABORTING, 0, false);
    removeActive(transaction.transactionId());
    transaction.transition(TransactionState.ABORTED, 0, true);
    result.set(
        databaseHigh,
        databaseLow,
        transaction.transactionId(),
        TransactionState.ABORTED,
        0);
    return StatusCode.OK;
  }

  private void capture(Transaction transaction, long visibleCommitSequence) {
    transaction.snapshot().capture(
        databaseHigh,
        databaseLow,
        nextSnapshotSequence++,
        visibleCommitSequence,
        activeTransactionIds,
        activeTransactionCount);
  }

  private boolean validActive(Transaction transaction) {
    return transaction != null
        && transaction.isOwnedBy(this)
        && transaction.state() == TransactionState.ACTIVE;
  }

  private void removeActive(long transactionId) {
    for (int index = 0; index < activeTransactionCount; index++) {
      if (activeTransactionIds[index] != transactionId) {
        continue;
      }
      int moved = activeTransactionCount - index - 1;
      for (int move = 0; move < moved; move++) {
        activeTransactionIds[index + move] = activeTransactionIds[index + move + 1];
      }
      activeTransactionCount--;
      activeTransactionIds[activeTransactionCount] = 0;
      return;
    }
  }

  private static boolean indeterminate(StatusCode status) {
    return status == StatusCode.IO_FAILURE
        || status == StatusCode.FENCED
        || status == StatusCode.CORRUPTION
        || status == StatusCode.INVARIANT_BROKEN;
  }
}
