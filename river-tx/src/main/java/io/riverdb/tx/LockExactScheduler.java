package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;

/** Targeted FIFO grant scheduler for one affected exact resource. */
final class LockExactScheduler {
  private final LockExactTable table;
  private final LockIntervalCursor affected = new LockIntervalCursor();
  private long workHead = -1;
  private long workTail = -1;
  private long deadlockHead = -1;
  private long deadlockTail = -1;
  private long targetedWakes;
  private long overlapSearches;
  private boolean draining;

  LockExactScheduler(LockExactTable owner) { table = owner; }

  void schedule(long resource) {
    enqueueAffected(resource);
    drain();
  }

  void schedule(long resource, long transaction) {
    enqueueAffected(resource);
    enqueueDeadlock(transaction);
    drain();
  }

  private void enqueueAffected(long resource) {
    if (!table.state.resources.occupied(resource)) return;
    LockExactResourceStore.Chunk chunk = table.state.resources.record(resource);
    int offset = LockTypedSlots.offset(resource);
    if (interval(chunk.scopes[offset])) {
      if (overlapSearches != Long.MAX_VALUE) overlapSearches++;
      table.state.intervals.overlaps(resource, affected);
      for (long overlap = affected.next(); overlap >= 0; overlap = affected.next()) {
        enqueue(overlap);
      }
    } else {
      enqueue(resource);
    }
  }

  private void drain() {
    if (draining) return;
    draining = true;
    try {
      while (workHead >= 0 || deadlockHead >= 0) {
        while (workHead >= 0) scheduleNextResource();
        if (deadlockHead >= 0) table.deadlocks.resolve(removeDeadlockHead());
      }
    } finally {
      draining = false;
    }
  }

  private void scheduleNextResource() {
    long resource = workHead;
    LockExactResourceStore.Chunk chunk = table.state.resources.record(resource);
    int offset = LockTypedSlots.offset(resource);
    workHead = LockTypedSlots.decode(chunk.schedulerWorkNext[offset]);
    if (workHead < 0) workTail = -1;
    chunk.schedulerWorkNext[offset] = 0;
    chunk.scheduled[offset] = 0;
    scheduleOne(resource);
    table.lifecycle.recycleResource(resource);
  }

  private void scheduleOne(long resource) {
    long request;
    boolean granted = false;
    while ((request = conversionHead(resource)) >= 0) {
      if (!canGrant(resource, request)) {
        if (granted) enqueueAffected(resource);
        enqueueDeadlock(requestTransaction(request));
        return;
      }
      grant(resource, request);
      granted = true;
      targetedWakes++;
    }
    while ((request = waitHead(resource)) >= 0 && canGrant(resource, request)) {
      grant(resource, request);
      granted = true;
      targetedWakes++;
    }
    if (granted) enqueueAffected(resource);
    request = waitHead(resource);
    if (request >= 0) enqueueDeadlock(requestTransaction(request));
  }

  boolean blocked(long resource, LockMode mode) {
    LockExactResourceStore.Chunk chunk = table.state.resources.record(resource);
    int offset = LockTypedSlots.offset(resource);
    return chunk.conversionHeads[offset] != 0 || chunk.waitHeads[offset] != 0
        || !LockExactCompatibility.grantable(
        mode.ordinal(), chunk.ownerCounts[offset],
        chunk.sharedCounts[offset], chunk.updateCounts[offset]);
  }

  private boolean canGrant(long resource, long request) {
    LockExactResourceStore.Chunk rc = table.state.resources.record(resource);
    LockExactRequestStore.Chunk qc = table.state.requests.record(request);
    int ro = LockTypedSlots.offset(resource);
    int qo = LockTypedSlots.offset(request);
    if (table.lifecycle.frozen(qc.transactions[qo])) return false;
    long holding = qc.holdings[qo];
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
    int ho = LockTypedSlots.offset(holding);
    if (interval(rc.scopes[ro])) {
      return (table.state.requests.conversion(request)
              || !table.conflicts.earlierBlocked(resource, request))
          && (table.state.requests.conversion(request)
              || !table.conflicts.conversionBlocked(resource, qc.transactions[qo]))
          && !table.conflicts.activeBlocked(resource, qc.transactions[qo], qc.modes[qo]);
    }
    if (hc.active[ho] == 0) return LockExactCompatibility.grantable(
        qc.modes[qo], rc.ownerCounts[ro], rc.sharedCounts[ro], rc.updateCounts[ro]);
    int held = hc.modes[ho];
    if (held >= qc.modes[qo]) return true;
    return LockExactCompatibility.upgradeable(
        qc.modes[qo], rc.ownerCounts[ro], rc.sharedCounts[ro], rc.updateCounts[ro]);
  }

  private long waitHead(long resource) {
    return LockTypedSlots.decode(table.state.resources.record(resource)
        .waitHeads[LockTypedSlots.offset(resource)]);
  }

  private long conversionHead(long resource) {
    return LockTypedSlots.decode(table.state.resources.record(resource)
        .conversionHeads[LockTypedSlots.offset(resource)]);
  }

  private void grant(long resource, long request) {
    LockExactRequestStore.Chunk qc = table.state.requests.record(request);
    int qo = LockTypedSlots.offset(request);
    long transaction = qc.transactions[qo];
    table.unlink.resourceRequest(resource, request);
    table.waitingCount--;
    table.holdingLifecycle.grant(resource, qc.holdings[qo], qc.modes[qo]);
    qc.states[qo] = (byte) LockWaitState.GRANTED.ordinal();
    table.waitCounters.granted();
    LockWaitHandle handle = qc.handles[qo];
    handle.transition(table.authority, LockExactTable.PROVIDER_GENERATION,
        table.lifecycle.transactionId(transaction), table.lifecycle.transactionGeneration(transaction),
        qc.laneIds[qo], qc.laneGenerations[qo], qc.requestGenerations[qo], request,
        LockWaitState.QUEUED, LockWaitState.GRANTED, StatusCode.OK);
    handle.unpark(table.authority, LockExactTable.PROVIDER_GENERATION,
        table.lifecycle.transactionId(transaction), table.lifecycle.transactionGeneration(transaction),
        qc.laneIds[qo], qc.laneGenerations[qo], qc.requestGenerations[qo], request);
  }

  private long requestTransaction(long request) {
    return table.state.requests.record(request).transactions[LockTypedSlots.offset(request)];
  }

  private void enqueueDeadlock(long transaction) {
    if (!table.state.transactions.occupied(transaction)) return;
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    long bit = 1L << offset;
    if ((chunk.deadlockScheduled[offset >>> 6] & bit) != 0) return;
    chunk.deadlockScheduled[offset >>> 6] |= bit;
    chunk.deadlockWorkNext[offset] = 0;
    if (deadlockTail < 0) deadlockHead = transaction;
    else {
      LockExactTransactionStore.Chunk tail = table.state.transactions.record(deadlockTail);
      tail.deadlockWorkNext[LockTypedSlots.offset(deadlockTail)] =
          LockTypedSlots.encode(transaction);
    }
    deadlockTail = transaction;
  }

  private long removeDeadlockHead() {
    long transaction = deadlockHead;
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    deadlockHead = LockTypedSlots.decode(chunk.deadlockWorkNext[offset]);
    if (deadlockHead < 0) deadlockTail = -1;
    chunk.deadlockWorkNext[offset] = 0;
    chunk.deadlockScheduled[offset >>> 6] &= ~(1L << offset);
    return transaction;
  }

  private void enqueue(long resource) {
    LockExactResourceStore.Chunk chunk = table.state.resources.record(resource);
    int offset = LockTypedSlots.offset(resource);
    if (chunk.scheduled[offset] != 0) return;
    chunk.scheduled[offset] = 1;
    chunk.schedulerWorkNext[offset] = 0;
    if (workTail < 0) workHead = resource;
    else {
      LockExactResourceStore.Chunk tail = table.state.resources.record(workTail);
      tail.schedulerWorkNext[LockTypedSlots.offset(workTail)] = LockTypedSlots.encode(resource);
    }
    workTail = resource;
  }

  private static boolean interval(byte scope) {
    return LockIntervalIndex.intervalScope(scope);
  }

  long targetedWakes() { return targetedWakes; }
  long overlapSearches() { return overlapSearches; }
}
