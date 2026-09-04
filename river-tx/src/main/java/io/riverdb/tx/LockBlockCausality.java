package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;
import java.util.Arrays;

/** Fixed-shape, allocation-stable counters for one explicit diagnostic phase. */
final class LockBlockCausality {
  private final long[] buckets = new long[LockBlockCausalitySnapshot.bucketCapacityValue()];
  private final long counterLimit;
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
  private boolean active;

  LockBlockCausality() { this(Long.MAX_VALUE); }

  LockBlockCausality(long maximumCounterValue) {
    if (maximumCounterValue <= 0) throw new IllegalArgumentException("invalid counter limit");
    counterLimit = maximumCounterValue;
  }

  StatusCode begin() {
    if (active) return StatusCode.CONFLICT;
    Arrays.fill(buckets, 0);
    entered = actualBlocks = handoffs = consumed = 0;
    timedOut = cancelled = deadlocked = failed = 0;
    revokedAfterHandoff = victimSelections = 0;
    blockedConsumed = blockedTimedOut = blockedCancelled = 0;
    blockedDeadlocked = blockedFailed = bucketTotal = 0;
    unclassified = overflows = 0;
    active = true;
    return StatusCode.OK;
  }

  StatusCode end(LockBlockCausalitySnapshot target) {
    if (!active || target == null) return StatusCode.CONFLICT;
    active = false;
    target.replaceFrom(this);
    return target.reconciles() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  StatusCode cancel() {
    if (!active) return StatusCode.OK;
    active = false;
    return StatusCode.OK;
  }

  boolean active() { return active; }

  void entered() {
    if (active) entered = increment(entered);
  }

  void block(
      LockScope scope,
      LockMode requestedMode,
      LockMode blockerMode,
      LockQueueKind waiterQueue,
      LockDeadlockEdgeKind relationship,
      LockGrantPrecondition precondition) {
    if (!active) return;
    int index = LockBlockCausalitySnapshot.index(
        scope, requestedMode, blockerMode, waiterQueue, relationship, precondition);
    if (index < 0) {
      actualBlocks = increment(actualBlocks);
      unclassified = increment(unclassified);
      return;
    }
    if (actualBlocks >= counterLimit || bucketTotal >= counterLimit
        || buckets[index] >= counterLimit) {
      overflow();
      return;
    }
    actualBlocks++;
    bucketTotal++;
    buckets[index]++;
  }

  void unclassifiedBlock() {
    if (!active) return;
    actualBlocks = increment(actualBlocks);
    unclassified = increment(unclassified);
  }

  void handoff() {
    if (active) handoffs = increment(handoffs);
  }

  void consumed(boolean blocked) {
    if (!active) return;
    consumed = increment(consumed);
    if (blocked) blockedConsumed = increment(blockedConsumed);
  }

  void terminal(StatusCode status, boolean blocked, boolean afterHandoff) {
    if (!active) return;
    if (afterHandoff) revokedAfterHandoff = increment(revokedAfterHandoff);
    if (status == StatusCode.TIMEOUT) {
      timedOut = increment(timedOut);
      if (blocked) blockedTimedOut = increment(blockedTimedOut);
    } else if (status == StatusCode.CANCELLED) {
      cancelled = increment(cancelled);
      if (blocked) blockedCancelled = increment(blockedCancelled);
    } else if (status == StatusCode.DEADLOCK) {
      deadlocked = increment(deadlocked);
      if (blocked) blockedDeadlocked = increment(blockedDeadlocked);
    } else {
      failed = increment(failed);
      if (blocked) blockedFailed = increment(blockedFailed);
    }
  }

  void victimSelected() {
    if (active) victimSelections = increment(victimSelections);
  }

  long[] buckets() { return buckets; }
  long enteredCount() { return entered; }
  long actualBlocks() { return actualBlocks; }
  long handoffs() { return handoffs; }
  long consumed() { return consumed; }
  long timedOut() { return timedOut; }
  long cancelled() { return cancelled; }
  long deadlocked() { return deadlocked; }
  long failed() { return failed; }
  long revokedAfterHandoff() { return revokedAfterHandoff; }
  long victimSelections() { return victimSelections; }
  long blockedConsumed() { return blockedConsumed; }
  long blockedTimedOut() { return blockedTimedOut; }
  long blockedCancelled() { return blockedCancelled; }
  long blockedDeadlocked() { return blockedDeadlocked; }
  long blockedFailed() { return blockedFailed; }
  long bucketTotal() { return bucketTotal; }
  long unclassifiedBlocks() { return unclassified; }
  long overflows() { return overflows; }

  private long increment(long value) {
    if (value >= counterLimit) {
      overflow();
      return value;
    }
    return value + 1;
  }

  private void overflow() {
    if (overflows < counterLimit) overflows++;
  }
}
