package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockDeadline;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;

/** Authenticated exact request identity, terminal cleanup, and lane index ownership. */
final class LockExactRequestLifecycle {
  private final LockExactTable table;

  LockExactRequestLifecycle(LockExactTable owner) { table = owner; }

  long authenticate(LockExecutionLane lane, LockWaitHandle handle) {
    if (lane == null || handle == null || !lane.isPending()) return -1;
    long request = lane.requestSlot();
    if (request < 0 || !table.state.requests.occupied(request)) return -1;
    LockExactRequestStore.Chunk chunk = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    long transaction = chunk.transactions[offset];
    long id = table.lifecycle.transactionId(transaction);
    long generation = table.lifecycle.transactionGeneration(transaction);
    long requestGeneration = chunk.requestGenerations[offset];
    return lane.matches(table.authority, LockExactTable.PROVIDER_GENERATION,
            id, generation, chunk.laneIds[offset], chunk.laneGenerations[offset],
            requestGeneration, request)
        && handle.matches(table.authority, LockExactTable.PROVIDER_GENERATION,
            id, generation, chunk.laneIds[offset], chunk.laneGenerations[offset],
            requestGeneration, request) ? request : -1;
  }

  StatusCode validateGranted(LockExecutionLane lane, LockWaitHandle handle) {
    long request = authenticate(lane, handle);
    if (request < 0 || handle.state() != LockWaitState.GRANTED) return StatusCode.NOT_OWNER;
    LockExactRequestStore.Chunk chunk = table.state.requests.record(request);
    return LockExactTable.WAIT_STATES[chunk.states[LockTypedSlots.offset(request)]]
        == LockWaitState.GRANTED ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  StatusCode consume(
      LockExecutionLane lane, LockWaitHandle handle, LockToken token) {
    long request = authenticate(lane, handle);
    if (request < 0 || handle.state() != LockWaitState.GRANTED) return StatusCode.NOT_OWNER;
    if (token == null || token.isActive()) return StatusCode.CONFLICT;
    LockExactRequestStore.Chunk chunk = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    if (LockExactTable.WAIT_STATES[chunk.states[offset]] != LockWaitState.GRANTED) {
      return StatusCode.NOT_OWNER;
    }
    StatusCode status = table.holdingLifecycle.issueToken(
        chunk.holdings[offset], chunk.referenceGenerations[offset], token, false);
    if (!status.isOk()) return status;
    long transaction = chunk.transactions[offset];
    table.unlink.request(chunk.resources[offset], transaction, request, false);
    removeIndex(request, chunk, offset);
    completeGranted(lane, handle, request, chunk, offset);
    table.state.requests.free(request);
    table.lifecycle.recycleTransaction(transaction);
    return StatusCode.OK;
  }

  StatusCode cancel(
      LockExecutionLane lane, LockWaitHandle handle, StatusCode outcome) {
    long request = authenticate(lane, handle);
    if (request < 0) return StatusCode.NOT_OWNER;
    LockExactRequestStore.Chunk chunk = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    LockWaitState current = LockExactTable.WAIT_STATES[chunk.states[offset]];
    if (current != LockWaitState.QUEUED && current != LockWaitState.GRANTED) {
      return StatusCode.NOT_OWNER;
    }
    long resource = chunk.resources[offset];
    long transaction = chunk.transactions[offset];
    table.unlink.request(resource, transaction, request, current == LockWaitState.QUEUED);
    if (current == LockWaitState.QUEUED) table.waitingCount--;
    table.holdingLifecycle.releaseReference(chunk.holdings[offset], false);
    publishTerminal(chunk, offset, request, outcome);
    removeIndex(request, chunk, offset);
    table.state.requests.free(request);
    table.scheduler.schedule(resource);
    table.lifecycle.recycle(resource, transaction);
    return outcome;
  }

  long remainingNanos(
      LockExecutionLane lane, LockWaitHandle handle, long nowNanos) {
    long request = authenticate(lane, handle);
    if (request < 0) return 0;
    LockExactRequestStore.Chunk chunk = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    if ((chunk.deadlinePresence[offset >>> 6] & (1L << offset)) == 0) {
      return -1;
    }
    return LockDeadline.remaining(chunk.deadlines[offset], nowNanos);
  }

  StatusCode arm(
      LockExecutionLane lane, LockWaitHandle handle, Thread waitingThread) {
    long request = authenticate(lane, handle);
    if (request < 0) return StatusCode.NOT_OWNER;
    LockExactRequestStore.Chunk chunk = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    if (LockExactTable.WAIT_STATES[chunk.states[offset]] != LockWaitState.QUEUED) {
      return StatusCode.NOT_OWNER;
    }
    long transaction = chunk.transactions[offset];
    return handle.arm(table.authority, LockExactTable.PROVIDER_GENERATION,
        table.lifecycle.transactionId(transaction), table.lifecycle.transactionGeneration(transaction),
        chunk.laneIds[offset], chunk.laneGenerations[offset],
        chunk.requestGenerations[offset], request, waitingThread);
  }

  StatusCode acknowledge(LockExecutionLane lane, LockWaitHandle handle) {
    if (lane == null || handle == null || !lane.isPending()
        || handle.state() == LockWaitState.QUEUED || handle.state() == LockWaitState.GRANTED) {
      return StatusCode.NOT_OWNER;
    }
    if (!handle.matchesIdentity(table.authority, LockExactTable.PROVIDER_GENERATION,
        lane.transactionId(), lane.transactionGeneration(), lane.laneId(),
        lane.laneGeneration(), lane.requestGeneration())) return StatusCode.NOT_OWNER;
    StatusCode status = lane.complete(table.authority, LockExactTable.PROVIDER_GENERATION,
        lane.transactionId(), lane.transactionGeneration(), lane.laneId(), lane.laneGeneration(),
        lane.requestGeneration(), lane.requestSlot());
    if (!status.isOk()) return status;
    status = handle.acknowledge(table.authority, LockExactTable.PROVIDER_GENERATION,
        lane.transactionId(), lane.transactionGeneration(), lane.laneId(),
        lane.laneGeneration(), lane.requestGeneration());
    return status.isOk() ? handle.status() : status;
  }

  void removeIndex(long request, LockExactRequestStore.Chunk chunk, int offset) {
    long transaction = chunk.transactions[offset];
    table.state.directory.laneIndex.remove(request, LockExactDirectory.laneHash(
        table.lifecycle.transactionId(transaction), table.lifecycle.transactionGeneration(transaction),
        chunk.laneIds[offset], chunk.laneGenerations[offset]));
  }

  int cancelAll(long transaction, StatusCode outcome) {
    LockExactTransactionStore.Chunk tc = table.state.transactions.record(transaction);
    int to = LockTypedSlots.offset(transaction);
    long request = LockTypedSlots.decode(tc.requestHeads[to]);
    int queuedCancelled = 0;
    while (request >= 0) {
      LockExactRequestStore.Chunk qc = table.state.requests.record(request);
      int qo = LockTypedSlots.offset(request);
      long next = LockTypedSlots.decode(qc.nextTransaction[qo]);
      LockWaitState waitState = LockExactTable.WAIT_STATES[qc.states[qo]];
      long resource = qc.resources[qo];
      table.unlink.request(resource, transaction, request, waitState == LockWaitState.QUEUED);
      if (waitState == LockWaitState.QUEUED) {
        table.waitingCount--;
        if (queuedCancelled != Integer.MAX_VALUE) queuedCancelled++;
      }
      table.holdingLifecycle.releaseReference(qc.holdings[qo], false);
      publishTerminal(qc, qo, request, outcome);
      removeIndex(request, qc, qo);
      table.state.requests.free(request);
      table.scheduler.schedule(resource);
      table.lifecycle.recycleResource(resource);
      request = next;
    }
    return queuedCancelled;
  }

  private void completeGranted(
      LockExecutionLane lane, LockWaitHandle handle, long request,
      LockExactRequestStore.Chunk chunk, int offset) {
    long transaction = chunk.transactions[offset];
    long id = table.lifecycle.transactionId(transaction);
    long generation = table.lifecycle.transactionGeneration(transaction);
    StatusCode completed = lane.complete(table.authority, LockExactTable.PROVIDER_GENERATION,
        id, generation, chunk.laneIds[offset], chunk.laneGenerations[offset],
        chunk.requestGenerations[offset], request);
    if (completed.isOk()) handle.completeGrant(table.authority, LockExactTable.PROVIDER_GENERATION,
        id, generation, chunk.laneIds[offset], chunk.laneGenerations[offset],
        chunk.requestGenerations[offset], request);
  }

  private void publishTerminal(
      LockExactRequestStore.Chunk chunk, int offset, long request, StatusCode status) {
    LockWaitState current = LockExactTable.WAIT_STATES[chunk.states[offset]];
    LockWaitState terminal = status == StatusCode.TIMEOUT ? LockWaitState.TIMED_OUT
        : status == StatusCode.CANCELLED ? LockWaitState.CANCELLED
        : status == StatusCode.DEADLOCK ? LockWaitState.DEADLOCK : LockWaitState.FAILED;
    table.waitCounters.terminal(status);
    if (chunk.actuallyBlocked[offset] != 0) {
      table.waitCounters.completeBlocked(chunk.blockedAtNanos[offset], System.nanoTime());
    }
    chunk.states[offset] = (byte) terminal.ordinal();
    LockWaitHandle handle = chunk.handles[offset];
    long transaction = chunk.transactions[offset];
    handle.transition(table.authority, LockExactTable.PROVIDER_GENERATION,
        table.lifecycle.transactionId(transaction), table.lifecycle.transactionGeneration(transaction),
        chunk.laneIds[offset], chunk.laneGenerations[offset],
        chunk.requestGenerations[offset], request, current, terminal, status);
    handle.unpark(table.authority, LockExactTable.PROVIDER_GENERATION,
        table.lifecycle.transactionId(transaction), table.lifecycle.transactionGeneration(transaction),
        chunk.laneIds[offset], chunk.laneGenerations[offset],
        chunk.requestGenerations[offset], request);
    handle.detach(table.authority, LockExactTable.PROVIDER_GENERATION,
        table.lifecycle.transactionId(transaction), table.lifecycle.transactionGeneration(transaction),
        chunk.laneIds[offset], chunk.laneGenerations[offset],
        chunk.requestGenerations[offset], request);
  }
}
