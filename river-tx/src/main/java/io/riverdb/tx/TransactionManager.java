package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;

/** Bounded lifecycle manager and commit/snapshot publication barrier. */
public final class TransactionManager {
  private final long databaseHigh;
  private final long databaseLow;
  private final long[] activeTransactionIds;
  private final LockManager locks;
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
    locks = new LockManager(maximumActive * 64);
  }

  public int maximumActiveTransactions() {
    return activeTransactionIds.length;
  }

  public synchronized int activeTransactionCount() {
    return activeTransactionCount;
  }

  public int activeLockCount() {
    return locks.activeLockCount();
  }

  public synchronized StatusCode tryAcquireKey(
      Transaction transaction,
      long tableId,
      long key,
      LockToken token) {
    if (!validActive(transaction) || key == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return locks.tryAcquire(
        transaction.transactionId(),
        LockScope.KEY,
        tableId,
        key,
        LockMode.EXCLUSIVE,
        0,
        0,
        token);
  }

  public synchronized StatusCode tryAcquireSharedKey(
      Transaction transaction,
      long tableId,
      long key,
      LockToken token) {
    if (!validActive(transaction) || key == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return locks.tryAcquire(
        transaction.transactionId(),
        LockScope.KEY,
        tableId,
        key,
        LockMode.SHARED,
        0,
        0,
        token);
  }

  public synchronized StatusCode upgradeKey(
      Transaction transaction,
      LockToken token) {
    if (!validActive(transaction)
        || token == null
        || token.transactionId() != transaction.transactionId()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return locks.upgrade(token, LockMode.EXCLUSIVE, 0, 0);
  }

  public StatusCode release(LockToken token) {
    return locks.release(token);
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

  /** Moves a validated fixed group to COMMITTING while retaining it in captured active sets. */
  public synchronized StatusCode beginCommitGroup(
      Transaction[] transactions,
      int count) {
    if (transactions == null || count <= 0 || count > transactions.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      if (!validActive(transactions[index])) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int previous = 0; previous < index; previous++) {
        if (transactions[previous] == transactions[index]) {
          return StatusCode.CONFLICT;
        }
      }
    }
    for (int index = 0; index < count; index++) {
      transactions[index].transition(TransactionState.COMMITTING, 0, false);
    }
    return StatusCode.OK;
  }

  /** Publishes one forced group and its transaction outcomes as one snapshot-barrier action. */
  public synchronized StatusCode publishCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      long[] commitSequences,
      int count,
      TransactionGroupCommitParticipant participant) {
    if (!validCommitGroup(transactions, results, commitSequences, count)
        || participant == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long previousCommitSequence = 0;
    for (int index = 0; index < count; index++) {
      long commitSequence = commitSequences[index];
      if (commitSequence <= previousCommitSequence
          || commitSequence <= transactions[index].snapshot().visibleCommitSequence()) {
        return failCommitGroup(
            transactions,
            results,
            count,
            StatusCode.INVARIANT_BROKEN);
      }
      previousCommitSequence = commitSequence;
    }
    StatusCode status = participant.publishForcedGroup();
    if (!status.isOk()) {
      return failCommitGroup(transactions, results, count, status);
    }
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      removeActive(transaction.transactionId());
      transaction.transition(
          TransactionState.COMMITTED,
          commitSequences[index],
          true);
      results[index].set(
          databaseHigh,
          databaseLow,
          transaction.transactionId(),
          TransactionState.COMMITTED,
          commitSequences[index]);
    }
    return StatusCode.OK;
  }

  /** Finalizes a group that could not establish or publish its durability outcome. */
  public synchronized StatusCode failCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure) {
    if (failure == null
        || failure.isOk()
        || transactions == null
        || results == null
        || count <= 0
        || count > transactions.length
        || count > results.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TransactionState state = indeterminate(failure)
        ? TransactionState.INDETERMINATE : TransactionState.ABORTED;
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      if (transaction == null
          || !transaction.isOwnedBy(this)
          || transaction.state() != TransactionState.COMMITTING
          || results[index] == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      removeActive(transaction.transactionId());
      transaction.transition(state, 0, true);
      results[index].set(
          databaseHigh,
          databaseLow,
          transaction.transactionId(),
          state,
          0);
    }
    return failure;
  }

  public synchronized StatusCode commitReadOnly(
      Transaction transaction,
      TransactionOutcome result) {
    if (!validActive(transaction) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long commitSequence = transaction.snapshot().visibleCommitSequence();
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

  /**
   * Commits a read-only optimistic transaction only if its validated source is unchanged.
   * The manager monitor makes validation atomic with every participant publication.
   */
  public synchronized StatusCode commitReadOnlyValidated(
      Transaction transaction,
      CommitSequenceSource source,
      long expectedCommitSequence,
      TransactionOutcome result) {
    if (!validActive(transaction)
        || source == null
        || expectedCommitSequence < 0
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.currentCommitSequence() != expectedCommitSequence) {
      result.reset();
      removeActive(transaction.transactionId());
      transaction.transition(TransactionState.ABORTED, 0, true);
      result.set(
          databaseHigh,
          databaseLow,
          transaction.transactionId(),
          TransactionState.ABORTED,
          0);
      return StatusCode.CONFLICT;
    }
    return commitReadOnly(transaction, result);
  }

  /**
   * Publishes a maintenance transaction only while no user transaction can retain a snapshot.
   */
  public synchronized StatusCode commitMaintenance(
      TransactionCommitParticipant participant,
      TransactionOutcome result) {
    if (participant == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (activeTransactionCount != 0 || locks.activeLockCount() != 0) {
      return StatusCode.RETRY;
    }
    if (nextTransactionId <= 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long transactionId = nextTransactionId++;
    if (nextTransactionId <= 0) {
      nextTransactionId = 0;
    }
    StatusCode status = participant.commit(transactionId);
    if (!status.isOk()) {
      TransactionState state = indeterminate(status)
          ? TransactionState.INDETERMINATE : TransactionState.ABORTED;
      result.set(databaseHigh, databaseLow, transactionId, state, 0);
      return status;
    }
    long commitSequence = participant.committedSequence();
    if (commitSequence <= 0) {
      result.set(
          databaseHigh,
          databaseLow,
          transactionId,
          TransactionState.INDETERMINATE,
          0);
      return StatusCode.INVARIANT_BROKEN;
    }
    result.set(
        databaseHigh,
        databaseLow,
        transactionId,
        TransactionState.COMMITTED,
        commitSequence);
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

  private boolean validCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      long[] commitSequences,
      int count) {
    if (transactions == null
        || results == null
        || commitSequences == null
        || count <= 0
        || count > transactions.length
        || count > results.length
        || count > commitSequences.length) {
      return false;
    }
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      if (transaction == null
          || !transaction.isOwnedBy(this)
          || transaction.state() != TransactionState.COMMITTING
          || results[index] == null) {
        return false;
      }
      results[index].reset();
    }
    return true;
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
