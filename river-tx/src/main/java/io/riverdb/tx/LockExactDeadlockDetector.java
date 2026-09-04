package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Iterative, allocation-free DFS over blocker relations derived from canonical chains. */
final class LockExactDeadlockDetector {
  private final LockExactTable table;
  private final LockExactBlockerCursor blockers;
  private final LockDeadlockDiagnostics diagnostics;
  private long nextEpoch = 1;
  private long victimSelections;
  private long cycleAncestor = -1;
  private long cycleCurrent = -1;

  LockExactDeadlockDetector(
      LockExactTable owner, LockDeadlockDiagnosticsConfig diagnosticsConfig) {
    table = owner;
    blockers = new LockExactBlockerCursor(owner);
    diagnostics = new LockDeadlockDiagnostics(owner, diagnosticsConfig);
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
    long selectionSequence = victimSelections == Long.MAX_VALUE
        ? Long.MAX_VALUE : victimSelections + 1;
    if (!diagnostics.prepareSelection(
        cycleAncestor, cycleCurrent, blockers, victim, selectionSequence)) return;
    table.lifecycle.deadlock(victim);
    if (victimSelections != Long.MAX_VALUE) victimSelections++;
    int cancelled = table.requestLifecycle.cancelAll(victim, StatusCode.DEADLOCK);
    int released = table.holdingLifecycle.releaseAll(victim);
    diagnostics.completeCleanup(victim, cancelled, released, cleanupValid(victim));
  }

  long victimSelections() { return victimSelections; }

  void snapshot(LockDeadlockDiagnosticsSnapshot target) { diagnostics.snapshot(target); }

  void transactionOutcome(long transaction, StatusCode status) {
    diagnostics.transactionOutcome(transaction, status);
  }

  boolean selfValidEdge(
      long waiter, long request, long blocker, long blockingResource,
      LockDeadlockEdgeKind kind, LockGrantPrecondition precondition) {
    return diagnostics.selfValidEdge(
        waiter, request, blocker, blockingResource, kind, precondition);
  }

  int admitSignatureForTest(long epoch, long fingerprint, long guard) {
    return diagnostics.admitSignatureForTest(epoch, fingerprint, guard);
  }

  private long cycleVictim(long start) {
    cycleAncestor = cycleCurrent = -1;
    long epoch = nextEpoch++;
    begin(start, -1, epoch);
    long current = start;
    while (current >= 0) {
      long blocker = blockers.next(current);
      if (blocker < 0) {
        finish(current, epoch);
        current = parent(current);
      } else if (visited(blocker, epoch)) {
        if (!finished(blocker, epoch)) {
          cycleAncestor = blocker;
          cycleCurrent = current;
          return youngest(blocker, current);
        }
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
    if (parent >= 0) {
      chunk.parentRequests[offset] = blockers.edgeRequest();
      chunk.parentBlockerRecords[offset] = blockers.edgeBlockerRecord();
      chunk.parentBlockingResources[offset] = blockers.edgeBlockingResource();
      chunk.parentEdgeKinds[offset] = blockers.edgeKind();
      chunk.parentPreconditions[offset] = blockers.edgePrecondition();
    }
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

  private boolean cleanupValid(long transaction) {
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    return chunk.requestHeads[offset] == 0 && chunk.holdingHeads[offset] == 0
        && chunk.lifecycleStates[offset] == LockExactLifecycle.DEADLOCK;
  }

}
