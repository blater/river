package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;

/** Caller-owned cold snapshot of one bounded lock-block classification phase. */
public final class LockBlockCausalitySnapshot {
  public static final int FORMAT_VERSION = 1;
  private static final LockScope[] SCOPES = LockScope.values();
  private static final LockMode[] MODES = LockMode.values();
  private static final LockGrantPrecondition[] PRECONDITIONS = LockGrantPrecondition.values();
  private static final int WAITER_QUEUE_COUNT = 2;
  private static final int BUCKET_COUNT = SCOPES.length * MODES.length * MODES.length
      * WAITER_QUEUE_COUNT * PRECONDITIONS.length;

  private final long[] buckets = new long[BUCKET_COUNT];
  private long entered;
  private long actualBlocks;
  private long handoffs;
  private long consumed;
  private long timedOut;
  private long cancelled;
  private long deadlocked;
  private long failed;
  private long revokedAfterHandoff;
  private long victimSelections;
  private long blockedConsumed;
  private long blockedTimedOut;
  private long blockedCancelled;
  private long blockedDeadlocked;
  private long blockedFailed;
  private long bucketTotal;
  private long unclassified;
  private long overflows;

  public int formatVersion() { return FORMAT_VERSION; }
  public int bucketCapacity() { return BUCKET_COUNT; }
  public long entered() { return entered; }
  public long actualBlocks() { return actualBlocks; }
  public long handoffs() { return handoffs; }
  public long consumed() { return consumed; }
  public long timedOut() { return timedOut; }
  public long cancelled() { return cancelled; }
  public long deadlocked() { return deadlocked; }
  public long failed() { return failed; }
  public long revokedAfterHandoff() { return revokedAfterHandoff; }
  public long victimSelections() { return victimSelections; }
  public long blockedConsumed() { return blockedConsumed; }
  public long blockedTimedOut() { return blockedTimedOut; }
  public long blockedCancelled() { return blockedCancelled; }
  public long blockedDeadlocked() { return blockedDeadlocked; }
  public long blockedFailed() { return blockedFailed; }
  public long bucketTotal() { return bucketTotal; }
  public long unclassifiedBlocks() { return unclassified; }
  public long overflows() { return overflows; }

  public long bucketCountAt(int index) {
    return index < 0 || index >= buckets.length ? -1 : buckets[index];
  }

  public LockScope scopeAt(int index) {
    return validIndex(index) ? SCOPES[index / scopeStride()] : null;
  }

  public LockMode requestedModeAt(int index) {
    return validIndex(index) ? MODES[index / requestedModeStride() % MODES.length] : null;
  }

  /** Held mode for an active owner, otherwise the requested mode of the queue blocker. */
  public LockMode blockerModeAt(int index) {
    return validIndex(index) ? MODES[index / blockerModeStride() % MODES.length] : null;
  }

  public LockQueueKind waiterQueueKindAt(int index) {
    if (!validIndex(index)) return null;
    return index / waiterQueueStride() % WAITER_QUEUE_COUNT == 0
        ? LockQueueKind.ORDINARY : LockQueueKind.CONVERSION;
  }

  public LockDeadlockEdgeKind queueRelationshipAt(int index) {
    LockGrantPrecondition precondition = grantPreconditionAt(index);
    if (precondition == null) return null;
    if (precondition == LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER) {
      return LockDeadlockEdgeKind.ACTIVE_OWNER;
    }
    if (precondition == LockGrantPrecondition.CONVERSION_QUEUE_EMPTY) {
      return LockDeadlockEdgeKind.CONVERSION_PRIORITY;
    }
    return LockDeadlockEdgeKind.FIFO_FAIRNESS;
  }

  public LockQueueKind blockerQueueKindAt(int index) {
    LockDeadlockEdgeKind relationship = queueRelationshipAt(index);
    if (relationship == null) return null;
    if (relationship == LockDeadlockEdgeKind.ACTIVE_OWNER) return LockQueueKind.ACTIVE_OWNER;
    if (relationship == LockDeadlockEdgeKind.CONVERSION_PRIORITY) {
      return LockQueueKind.CONVERSION;
    }
    return waiterQueueKindAt(index);
  }

  public LockGrantPrecondition grantPreconditionAt(int index) {
    return validIndex(index) ? PRECONDITIONS[index % PRECONDITIONS.length] : null;
  }

  public boolean reconciles() {
    return overflows == 0
        && unclassified == 0
        && failed == 0
        && entered == consumed + timedOut + cancelled + deadlocked + failed
        && handoffs == consumed + revokedAfterHandoff
        && actualBlocks == bucketTotal
        && actualBlocks == blockedConsumed + blockedTimedOut + blockedCancelled
            + blockedDeadlocked + blockedFailed
        && victimSelections <= deadlocked;
  }

  public long bucketCount(
      LockScope scope,
      LockMode requestedMode,
      LockMode blockerMode,
      LockQueueKind waiterQueue,
      LockDeadlockEdgeKind relationship,
      LockGrantPrecondition precondition) {
    int index = index(
        scope, requestedMode, blockerMode, waiterQueue, relationship, precondition);
    return index < 0 ? -1 : buckets[index];
  }

  void replaceFrom(LockBlockCausality source) {
    System.arraycopy(source.buckets(), 0, buckets, 0, buckets.length);
    entered = source.enteredCount();
    actualBlocks = source.actualBlocks();
    handoffs = source.handoffs();
    consumed = source.consumed();
    timedOut = source.timedOut();
    cancelled = source.cancelled();
    deadlocked = source.deadlocked();
    failed = source.failed();
    revokedAfterHandoff = source.revokedAfterHandoff();
    victimSelections = source.victimSelections();
    blockedConsumed = source.blockedConsumed();
    blockedTimedOut = source.blockedTimedOut();
    blockedCancelled = source.blockedCancelled();
    blockedDeadlocked = source.blockedDeadlocked();
    blockedFailed = source.blockedFailed();
    bucketTotal = source.bucketTotal();
    unclassified = source.unclassifiedBlocks();
    overflows = source.overflows();
  }

  static int bucketCapacityValue() { return BUCKET_COUNT; }

  static int index(
      LockScope scope,
      LockMode requestedMode,
      LockMode blockerMode,
      LockQueueKind waiterQueue,
      LockDeadlockEdgeKind relationship,
      LockGrantPrecondition precondition) {
    if (scope == null || requestedMode == null || blockerMode == null
        || relationship == null || precondition == null
        || waiterQueue != LockQueueKind.ORDINARY
            && waiterQueue != LockQueueKind.CONVERSION
        || relationship != relationship(precondition)) return -1;
    int index = scope.ordinal();
    index = index * MODES.length + requestedMode.ordinal();
    index = index * MODES.length + blockerMode.ordinal();
    index = index * WAITER_QUEUE_COUNT
        + (waiterQueue == LockQueueKind.ORDINARY ? 0 : 1);
    return index * PRECONDITIONS.length + precondition.ordinal();
  }

  private boolean validIndex(int index) { return index >= 0 && index < buckets.length; }
  private static int waiterQueueStride() { return PRECONDITIONS.length; }
  private static int blockerModeStride() { return WAITER_QUEUE_COUNT * waiterQueueStride(); }
  private static int requestedModeStride() { return MODES.length * blockerModeStride(); }
  private static int scopeStride() { return MODES.length * requestedModeStride(); }

  private static LockDeadlockEdgeKind relationship(LockGrantPrecondition precondition) {
    if (precondition == LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER) {
      return LockDeadlockEdgeKind.ACTIVE_OWNER;
    }
    if (precondition == LockGrantPrecondition.CONVERSION_QUEUE_EMPTY) {
      return LockDeadlockEdgeKind.CONVERSION_PRIORITY;
    }
    return LockDeadlockEdgeKind.FIFO_FAIRNESS;
  }
}
