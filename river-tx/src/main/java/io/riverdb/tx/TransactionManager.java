package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.lock.LockDeadline;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;

/** Bounded lifecycle manager and commit/snapshot publication barrier. */
public final class TransactionManager {
  public static final long DEFAULT_LOCK_WAIT_TIMEOUT_NANOS = LockDeadline.DEFAULT_WAIT_NANOS;
  private static final long DEFAULT_LOCK_MEMORY_BYTES = 64L << 20;
  final long databaseHigh;
  final long databaseLow;
  private final TransactionSnapshotLifecycle snapshots;
  final LockManager locks;
  private final long lockWaitTimeoutNanos;
  private final TransactionCompletion completion;
  private final LockRequest directLockRequest = new LockRequest();
  private long nextTransactionId;
  private long nextTransactionStartOrder = 1;

  public TransactionManager(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long firstTransactionId,
      int maximumActive) {
    this(databaseIncarnationHigh, databaseIncarnationLow, firstTransactionId,
        maximumActive, new LockMemoryEnvelope(DEFAULT_LOCK_MEMORY_BYTES),
        DEFAULT_LOCK_WAIT_TIMEOUT_NANOS);
  }

  /** Constructs a manager with an independently byte-bounded lock provider. */
  public TransactionManager(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long firstTransactionId,
      int maximumActive,
      LockMemoryEnvelope lockMemory) {
    this(databaseIncarnationHigh, databaseIncarnationLow, firstTransactionId,
        maximumActive, lockMemory, DEFAULT_LOCK_WAIT_TIMEOUT_NANOS);
  }

  /** Constructs a manager with the default lock envelope and a chosen wait duration. */
  public TransactionManager(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long firstTransactionId,
      int maximumActive,
      long lockWaitTimeoutNanos) {
    this(databaseIncarnationHigh, databaseIncarnationLow, firstTransactionId,
        maximumActive, new LockMemoryEnvelope(DEFAULT_LOCK_MEMORY_BYTES),
        lockWaitTimeoutNanos);
  }

  /** Constructs a manager with independently bounded lock bytes and wait duration. */
  public TransactionManager(
      long databaseIncarnationHigh,
      long databaseIncarnationLow,
      long firstTransactionId,
      int maximumActive,
      LockMemoryEnvelope lockMemory,
      long lockWaitTimeoutNanos) {
    if (maximumActive <= 0) {
      throw new IllegalArgumentException("invalid active transaction capacity");
    }
    if (lockMemory == null || lockWaitTimeoutNanos <= 0) {
      throw new IllegalArgumentException("invalid lock memory or wait timeout");
    }
    databaseHigh = databaseIncarnationHigh;
    databaseLow = databaseIncarnationLow;
    nextTransactionId = firstTransactionId;
    snapshots = new TransactionSnapshotLifecycle(
        databaseIncarnationHigh, databaseIncarnationLow, maximumActive);
    locks = new LockManager(lockMemory);
    this.lockWaitTimeoutNanos = lockWaitTimeoutNanos;
    completion = new TransactionCompletion(this);
  }

  public int maximumActiveTransactions() {
    return snapshots.capacity();
  }

  public synchronized int activeTransactionCount() {
    return snapshots.count();
  }

  /** Oldest commit sequence still visible to an active transaction. */
  public synchronized long oldestVisibleCommitSequence() {
    return snapshots.oldestVisibleCommitSequence();
  }

  public long activeLockCount() {
    return locks.activeLockCount();
  }

  public long waitingLockCount() {
    return locks.waitingCount();
  }

  public long deadlockVictimSelections() {
    return locks.deadlockVictimSelections();
  }

  /** Intended authenticated lock boundary; callers pair it with {@link Transaction#context()}. */
  public LockService lockService() { return locks; }

  public long lockWaitTimeoutNanos() { return lockWaitTimeoutNanos; }

  public synchronized StatusCode tryAcquireKey(
      Transaction transaction,
      long space,
      long key,
      LockToken token) {
    if (!validActive(transaction)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    directLockRequest.setKey(space, key, LockMode.EXCLUSIVE, 0);
    return locks.exact.tryAcquire(
        transaction.transactionId(), transaction.transactionGeneration(),
        transaction.transactionStartOrder(), directLockRequest, token);
  }

  public synchronized StatusCode tryAcquireSharedKey(
      Transaction transaction,
      long space,
      long key,
      LockToken token) {
    if (!validActive(transaction)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    directLockRequest.setKey(space, key, LockMode.SHARED, 0);
    return locks.exact.tryAcquire(
        transaction.transactionId(), transaction.transactionGeneration(),
        transaction.transactionStartOrder(), directLockRequest, token);
  }

  public synchronized StatusCode tryAcquireSharedRange(
      Transaction transaction,
      long lowerSpace,
      long lowerKey,
      long upperSpace,
      long upperKey,
      LockToken token) {
    if (!validActive(transaction)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    directLockRequest.setRange(
        lowerSpace, lowerKey, upperSpace, upperKey, LockMode.SHARED, 0);
    return locks.exact.tryAcquire(
        transaction.transactionId(), transaction.transactionGeneration(),
        transaction.transactionStartOrder(), directLockRequest, token);
  }

  public synchronized StatusCode upgradeKey(
      Transaction transaction,
      LockToken token) {
    if (!validActive(transaction)
        || token == null
        || token.transactionId() != transaction.transactionId()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return locks.upgrade(token, LockMode.EXCLUSIVE);
  }

  public synchronized StatusCode release(Transaction transaction, LockToken token) {
    if (transaction == null || !transaction.isOwnedIdentityBy(this)
        || transaction.state() == TransactionState.COMMITTING
        || transaction.state() == TransactionState.ABORTING
        || token == null
        || token.transactionId() != transaction.transactionId()
        || token.transactionGeneration() != transaction.transactionGeneration()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return locks.release(token);
  }

  public synchronized boolean hasLockConflict(Transaction transaction) {
    return validActive(transaction) && locks.lifecycle.hasCommitBlocker(transaction);
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
    if (snapshots.full()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (nextTransactionId <= 0 || nextTransactionStartOrder <= 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long transactionId = nextTransactionId++;
    long transactionStartOrder = nextTransactionStartOrder++;
    if (nextTransactionId <= 0) {
      nextTransactionId = 0;
    }
    if (nextTransactionStartOrder <= 0) nextTransactionStartOrder = 0;
    StatusCode status = result.prepareClaim(
        this, transactionId, transactionStartOrder, isolationLevel);
    if (!status.isOk()) return status;
    status = snapshots.admit(result, visibleCommitSequence);
    if (!status.isOk()) {
      result.abandonClaim();
      return status;
    }
    status = locks.lifecycle.activate(result, databaseHigh, databaseLow);
    if (!status.isOk()) {
      snapshots.remove(transactionId);
      result.abandonClaim();
    }
    return status;
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
    return snapshots.refresh(
        this, locks, transaction, IsolationLevel.READ_COMMITTED, visibleCommitSequence);
  }

  public synchronized StatusCode refreshReadCommitted(
      Transaction transaction,
      CommitSequenceSource source) {
    if (source == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return refreshReadCommitted(transaction, source.currentCommitSequence());
  }

  /** Captures the current frontier after a serializable read has acquired its protection. */
  public synchronized StatusCode refreshSerializableAfterProtection(
      Transaction transaction,
      CommitSequenceSource source) {
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return snapshots.refresh(
        this, locks, transaction, IsolationLevel.SERIALIZABLE,
        source.currentCommitSequence());
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
    StatusCode admission = locks.lifecycle.freezeForCommit(transaction);
    if (admission == StatusCode.CONFLICT) {
      return completion.abortFrozenForConflict(transaction, result);
    }
    if (!admission.isOk()) return admission;
    transaction.transition(TransactionState.COMMITTING, 0, false);
    StatusCode status = participant.commit(transaction.transactionId());
    if (!status.isOk()) {
      completion.finish(transaction, result,
          indeterminate(status) ? TransactionState.INDETERMINATE : TransactionState.ABORTED,
          0, status);
      return status;
    }
    long commitSequence = participant.committedSequence();
    if (commitSequence <= transaction.snapshot().visibleCommitSequence()) {
      completion.finish(transaction, result, TransactionState.INDETERMINATE, 0,
          StatusCode.INVARIANT_BROKEN);
      return StatusCode.INVARIANT_BROKEN;
    }
    completion.finish(transaction, result, TransactionState.COMMITTED, commitSequence,
        StatusCode.CANCELLED);
    return StatusCode.OK;
  }

  public synchronized StatusCode abort(Transaction transaction, TransactionOutcome result) {
    if (!validActive(transaction) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode frozen = locks.lifecycle.freezeForAbort(transaction);
    if (!frozen.isOk()) return frozen;
    transaction.transition(TransactionState.ABORTING, 0, false);
    completion.finish(transaction, result, TransactionState.ABORTED, 0, StatusCode.CANCELLED);
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
    StatusCode frozen = locks.lifecycle.freezeGroup(transactions, count);
    if (!frozen.isOk()) return frozen;
    for (int index = 0; index < count; index++) {
      transactions[index].transition(TransactionState.COMMITTING, 0, false);
    }
    return StatusCode.OK;
  }

  /** Installs one prepared group and its outcomes as one bounded snapshot-barrier action. */
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
        return failForcedCommitGroup(
            transactions,
            results,
            count,
            StatusCode.INVARIANT_BROKEN);
      }
      previousCommitSequence = commitSequence;
    }
    StatusCode status = participant.installPreparedGroup();
    if (!status.isOk()) {
      return failForcedCommitGroup(transactions, results, count, status);
    }
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      completion.finish(transaction, results[index], TransactionState.COMMITTED,
          commitSequences[index], StatusCode.CANCELLED);
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
      completion.finish(transaction, results[index], state, 0, failure);
    }
    return failure;
  }

  public synchronized StatusCode failForcedCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure) {
    if (failure == null || failure.isOk()
        || transactions == null || results == null || count <= 0
        || count > transactions.length || count > results.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      if (transaction == null || !transaction.isOwnedBy(this)
          || transaction.state() != TransactionState.COMMITTING
          || results[index] == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      completion.finish(
          transaction, results[index], TransactionState.INDETERMINATE, 0, failure);
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
    StatusCode admission = locks.lifecycle.freezeForCommit(transaction);
    if (admission == StatusCode.CONFLICT) {
      return completion.abortFrozenForConflict(transaction, result);
    }
    if (!admission.isOk()) return admission;
    long commitSequence = transaction.snapshot().visibleCommitSequence();
    completion.finish(transaction, result, TransactionState.COMMITTED, commitSequence,
        StatusCode.CANCELLED);
    return StatusCode.OK;
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
    if (snapshots.count() != 0 || locks.activeLockCount() != 0) {
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

  void removeActive(long transactionId) {
    snapshots.remove(transactionId);
  }

  private static boolean indeterminate(StatusCode status) {
    return status == StatusCode.IO_FAILURE
        || status == StatusCode.FENCED
        || status == StatusCode.CORRUPTION
        || status == StatusCode.INVARIANT_BROKEN;
  }
}
