package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockWaitHandle;

/** Exact transaction identity, freeze state, terminal cleanup, and empty-record recycling. */
final class LockExactLifecycle {
  static final byte FROZEN = 1;
  static final byte DEADLOCK = 2;
  private final LockExactTable table;

  LockExactLifecycle(LockExactTable owner) { table = owner; }

  long transactionId(long transaction) {
    return table.state.transactions.record(transaction)
        .transactionIds[LockTypedSlots.offset(transaction)];
  }

  long transactionGeneration(long transaction) {
    return table.state.transactions.record(transaction)
        .transactionGenerations[LockTypedSlots.offset(transaction)];
  }

  void freeze(long id, long generation) {
    long transaction = table.state.directory.transaction(id, generation);
    if (transaction < 0) return;
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    if (chunk.lifecycleStates[offset] != DEADLOCK) chunk.lifecycleStates[offset] = FROZEN;
  }

  boolean frozen(long id, long generation) {
    long transaction = table.state.directory.transaction(id, generation);
    return transaction >= 0 && frozen(transaction);
  }

  boolean frozen(LockExecutionLane lane, LockWaitHandle handle) {
    return lane != null && handle != null
        && frozen(lane.transactionId(), lane.transactionGeneration());
  }

  boolean frozen(long transaction) {
    return table.state.transactions.record(transaction)
        .lifecycleStates[LockTypedSlots.offset(transaction)] != 0;
  }

  void deadlock(long transaction) {
    table.state.transactions.record(transaction)
        .lifecycleStates[LockTypedSlots.offset(transaction)] = DEADLOCK;
  }

  boolean deadlocked(long id, long generation) {
    long transaction = table.state.directory.transaction(id, generation);
    return transaction >= 0 && table.state.transactions.record(transaction)
        .lifecycleStates[LockTypedSlots.offset(transaction)] == DEADLOCK;
  }

  StatusCode blockedStatus(long id, long generation) {
    long transaction = table.state.directory.transaction(id, generation);
    if (transaction < 0) return StatusCode.OK;
    return blockedStatus(transaction);
  }

  StatusCode blockedStatus(long transaction) {
    byte state = table.state.transactions.record(transaction)
        .lifecycleStates[LockTypedSlots.offset(transaction)];
    return state == DEADLOCK ? StatusCode.DEADLOCK
        : state == FROZEN ? StatusCode.CONFLICT : StatusCode.OK;
  }

  StatusCode blockedStatus(LockExecutionLane lane, LockWaitHandle handle) {
    return lane == null || handle == null ? StatusCode.OK
        : blockedStatus(lane.transactionId(), lane.transactionGeneration());
  }

  boolean hasPendingRequests(long id, long generation) {
    long transaction = table.state.directory.transaction(id, generation);
    if (transaction < 0) return false;
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    return chunk.requestHeads[LockTypedSlots.offset(transaction)] != 0;
  }

  void releaseAll(long id, long generation, StatusCode outcome) {
    long transaction = table.state.directory.transaction(id, generation);
    if (transaction < 0) return;
    table.requestLifecycle.cancelAll(transaction, outcome);
    table.holdingLifecycle.releaseAll(transaction);
    recycleTransaction(transaction);
  }

  void recycle(long resource, long transaction) {
    recycleResource(resource);
    recycleTransaction(transaction);
  }

  void recycleResource(long resource) {
    if (!table.state.resources.occupied(resource)) return;
    LockExactResourceStore.Chunk chunk = table.state.resources.record(resource);
    int offset = LockTypedSlots.offset(resource);
    if (chunk.scheduled[offset] != 0 || chunk.ownerHeads[offset] != 0
        || chunk.conversionHeads[offset] != 0 || chunk.waitHeads[offset] != 0) return;
    long hash = chunk.hashes[offset];
    if (LockIntervalIndex.intervalScope(chunk.scopes[offset])) {
      table.state.intervals.remove(resource);
    }
    table.state.directory.resourceIndex.remove(resource, hash);
    table.state.resources.free(resource);
  }

  void recycleTransaction(long transaction) {
    if (!table.state.transactions.occupied(transaction)) return;
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    if ((chunk.deadlockScheduled[offset >>> 6] & (1L << offset)) != 0
        || chunk.holdingHeads[offset] != 0 || chunk.requestHeads[offset] != 0) return;
    table.state.directory.transactionIndex.remove(transaction,
        LockExactDirectory.transactionHash(
            chunk.transactionIds[offset], chunk.transactionGenerations[offset]));
    table.state.transactions.free(transaction);
  }
}
