package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Iterative, allocation-free DFS over blocker relations derived from canonical chains. */
final class LockExactDeadlockDetector {
  private final LockExactTable table;
  private final LockExactBlockerCursor blockers;
  private long nextEpoch = 1;
  private long victimSelections;

  LockExactDeadlockDetector(LockExactTable owner) {
    table = owner;
    blockers = new LockExactBlockerCursor(owner);
  }

  StatusCode canAdmit(
      long id, long generation, long startOrder, boolean needsDetection) {
    if (startOrder <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    long transaction = table.state.directory.transaction(id, generation);
    if (transaction >= 0) {
      LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
      if (chunk.startOrders[LockTypedSlots.offset(transaction)] != startOrder) {
        return StatusCode.NOT_OWNER;
      }
    }
    return needsDetection && nextEpoch == Long.MAX_VALUE
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  void initializeTransaction(long transaction, long startOrder) {
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    if (chunk.startOrders[offset] == 0) chunk.startOrders[offset] = startOrder;
  }

  void resolve(long start) {
    if (!table.state.transactions.occupied(start) || table.lifecycle.frozen(start)) return;
    long victim = cycleVictim(start);
    if (victim < 0) return;
    table.lifecycle.deadlock(victim);
    if (victimSelections != Long.MAX_VALUE) victimSelections++;
    table.requestLifecycle.cancelAll(victim, StatusCode.DEADLOCK);
    table.holdingLifecycle.releaseAll(victim);
  }

  long victimSelections() { return victimSelections; }

  private long cycleVictim(long start) {
    long epoch = nextEpoch++;
    begin(start, -1, epoch);
    long current = start;
    while (current >= 0) {
      long blocker = blockers.next(current);
      if (blocker < 0) {
        finish(current, epoch);
        current = parent(current);
      } else if (visited(blocker, epoch)) {
        if (!finished(blocker, epoch)) return youngest(blocker, current);
      } else {
        begin(blocker, current, epoch);
        current = blocker;
      }
    }
    return -1;
  }

  private void begin(long transaction, long parent, long epoch) {
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    chunk.visitEpochs[offset] = epoch;
    chunk.finishEpochs[offset] = 0;
    chunk.parents[offset] = LockTypedSlots.encode(parent);
    blockers.begin(transaction);
  }

  private void finish(long transaction, long epoch) {
    table.state.transactions.record(transaction)
        .finishEpochs[LockTypedSlots.offset(transaction)] = epoch;
  }

  private boolean visited(long transaction, long epoch) {
    return table.state.transactions.record(transaction)
        .visitEpochs[LockTypedSlots.offset(transaction)] == epoch;
  }

  private boolean finished(long transaction, long epoch) {
    return table.state.transactions.record(transaction)
        .finishEpochs[LockTypedSlots.offset(transaction)] == epoch;
  }

  private long parent(long transaction) {
    return LockTypedSlots.decode(table.state.transactions.record(transaction)
        .parents[LockTypedSlots.offset(transaction)]);
  }

  private long youngest(long ancestor, long current) {
    long victim = ancestor;
    long cursor = current;
    while (true) {
      if (younger(cursor, victim)) victim = cursor;
      if (cursor == ancestor) return victim;
      cursor = parent(cursor);
    }
  }

  private boolean younger(long candidate, long current) {
    LockExactTransactionStore.Chunk left = table.state.transactions.record(candidate);
    LockExactTransactionStore.Chunk right = table.state.transactions.record(current);
    int lo = LockTypedSlots.offset(candidate);
    int ro = LockTypedSlots.offset(current);
    if (left.startOrders[lo] != right.startOrders[ro]) {
      return left.startOrders[lo] > right.startOrders[ro];
    }
    if (left.transactionIds[lo] != right.transactionIds[ro]) {
      return left.transactionIds[lo] > right.transactionIds[ro];
    }
    return left.transactionGenerations[lo] > right.transactionGenerations[ro];
  }
}
