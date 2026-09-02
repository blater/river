package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;

/** Reversible compound admission for exact holdings and lane requests. */
final class LockExactAdmissionController {
  private final LockExactTable table;
  private final LockExactHoldingAdmission holdingAdmission;
  private final LockExactAdmissionTicket ticket;
  final LockExactIndexAdmission indexes;

  LockExactAdmissionController(LockExactTable owner) {
    table = owner;
    ticket = new LockExactAdmissionTicket(owner);
    indexes = new LockExactIndexAdmission(owner);
    holdingAdmission = new LockExactHoldingAdmission(owner, this);
  }

  StatusCode enqueue(
      long id, long generation, long startOrder, long laneId, long laneGeneration,
      LockRequest request, LockExecutionLane lane, LockWaitHandle handle) {
    return enqueue(id, generation, startOrder, laneId, laneGeneration,
        request, lane, handle, System.nanoTime());
  }

  StatusCode enqueue(
      long id, long generation, long startOrder, long laneId, long laneGeneration,
      LockRequest request, LockExecutionLane lane, LockWaitHandle handle,
      long blockedClockNanos) {
    StatusCode status = validate(
        id, generation, startOrder, laneId, laneGeneration, request, lane, handle);
    if (!status.isOk()) return status;
    table.admission.reset();
    status = prepareSlots(id, generation, request);
    if (!status.isOk()) return fail(status);
    LockExactAdmission a = table.admission;
    status = indexes.reserve(a.newResource, a.newTransaction, a.newHolding,
        a.resourceSlot, a.transactionSlot, a.holdingSlot, a.requestSlot, id, generation,
        laneId, laneGeneration, request);
    if (!status.isOk()) return fail(status);
    long requestGeneration = table.nextRequest;
    status = lane.bind(table.authority, LockExactTable.PROVIDER_GENERATION,
        id, generation, laneId, laneGeneration, requestGeneration, a.requestSlot);
    if (!status.isOk()) return fail(status);
    status = handle.bind(table.authority, LockExactTable.PROVIDER_GENERATION,
        id, generation, laneId, laneGeneration, requestGeneration, a.requestSlot);
    if (!status.isOk()) {
      lane.complete(table.authority, LockExactTable.PROVIDER_GENERATION,
          id, generation, laneId, laneGeneration, requestGeneration, a.requestSlot);
      return fail(status);
    }
    initialize(a.resourceSlot, a.transactionSlot, id, generation, startOrder, request,
        a.newResource, a.newTransaction);
    if (a.newHolding) table.state.initializeReservedHolding(
        a.holdingSlot, a.resourceSlot, a.transactionSlot,
        request.mode(), table.nextCapability, 1);
    else table.state.holdings.record(a.holdingSlot)
        .references[LockTypedSlots.offset(a.holdingSlot)]++;
    table.state.initializeRequest(
        a.requestSlot, a.resourceSlot, a.transactionSlot, a.holdingSlot,
        laneId, laneGeneration, requestGeneration, table.nextReference, request, handle,
        conversion(a, request));
    indexes.commit(a.newResource, a.newTransaction, a.newHolding,
        a.resourceSlot, a.transactionSlot, a.holdingSlot, a.requestSlot, id, generation,
        laneId, laneGeneration, request);
    table.state.linkRequest(a.resourceSlot, a.transactionSlot, a.requestSlot);
    boolean createdHolding = a.newHolding;
    long affectedResource = a.resourceSlot;
    long affectedTransaction = a.transactionSlot;
    long admittedRequest = a.requestSlot;
    commitSlots();
    table.nextRequest++;
    table.nextReference++;
    if (createdHolding) table.nextCapability++;
    table.waitingCount++;
    table.waitCounters.entered();
    table.scheduler.schedule(affectedResource, affectedTransaction);
    if (table.state.requests.occupied(admittedRequest)) {
      LockExactRequestStore.Chunk requests = table.state.requests.record(admittedRequest);
      int offset = LockTypedSlots.offset(admittedRequest);
      if (LockExactTable.WAIT_STATES[requests.states[offset]] == LockWaitState.QUEUED) {
        requests.blockedAtNanos[offset] = blockedClockNanos;
        requests.actuallyBlocked[offset] = 1;
        table.waitCounters.blocked();
      }
    }
    return handle.status();
  }

  private StatusCode validate(
      long id, long generation, long startOrder, long laneId, long laneGeneration,
      LockRequest request, LockExecutionLane lane, LockWaitHandle handle) {
    if (!LockExactTable.valid(id, generation, request) || laneId < 0 || laneGeneration <= 0
        || lane == null || handle == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (lane.isPending() || handle.state() != LockWaitState.IDLE) return StatusCode.CONFLICT;
    if (table.nextRequest <= 0 || table.nextRequest == Long.MAX_VALUE
        || table.nextReference <= 0 || table.nextReference == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (table.state.directory.lane(id, generation, laneId, laneGeneration) >= 0) {
      return StatusCode.CONFLICT;
    }
    return table.deadlocks.canAdmit(id, generation, startOrder, true);
  }

  private StatusCode prepareSlots(long id, long generation, LockRequest request) {
    LockExactAdmission a = table.admission;
    a.resourceSlot = table.state.directory.resource(request);
    a.transactionSlot = table.state.directory.transaction(id, generation);
    a.holdingSlot = a.resourceSlot < 0 ? -1
        : table.state.directory.holding(a.resourceSlot, id, generation);
    a.newResource = a.resourceSlot < 0;
    a.newTransaction = a.transactionSlot < 0;
    StatusCode status;
    if (a.newResource) {
      status = table.state.resources.reserve(a.resource);
      if (!status.isOk()) return status;
      a.resourceSlot = a.resource.slot;
      status = table.state.resources.prepareTuple(a.resourceSlot, request);
      if (!status.isOk()) return status;
    }
    if (a.newTransaction) {
      status = table.state.transactions.reserve(a.transaction);
      if (!status.isOk()) return status;
      a.transactionSlot = a.transaction.slot;
    }
    status = table.state.requests.reserve(a.request);
    if (!status.isOk()) return status;
    a.requestSlot = a.request.slot;
    a.newHolding = a.holdingSlot < 0;
    if (a.newHolding && (table.nextCapability <= 0 || table.nextCapability == Long.MAX_VALUE)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!a.newHolding && references(a.holdingSlot) == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!a.newHolding) return StatusCode.OK;
    status = table.state.holdings.reserve(a.holding);
    if (status.isOk()) a.holdingSlot = a.holding.slot;
    return status;
  }

  StatusCode createHolding(
      long id, long generation, long startOrder,
      long resource, LockRequest request, LockToken token) {
    return holdingAdmission.create(id, generation, startOrder, resource, request, token);
  }

  void initialize(
      long resource, long transaction, long id, long generation, long startOrder,
      LockRequest request, boolean newResource, boolean newTransaction) {
    if (newResource) table.state.initializeResource(resource, request);
    if (newTransaction) {
      table.state.initializeTransaction(transaction, id, generation);
      table.deadlocks.initializeTransaction(transaction, startOrder);
    }
  }

  StatusCode fail(StatusCode status) {
    return ticket.rollback(status);
  }

  void commitSlots() {
    ticket.commit();
  }

  private long references(long holding) {
    return table.state.holdings.record(holding).references[LockTypedSlots.offset(holding)];
  }

  private boolean conversion(LockExactAdmission admission, LockRequest request) {
    if (admission.newHolding) return false;
    LockExactHoldingStore.Chunk chunk = table.state.holdings.record(admission.holdingSlot);
    int offset = LockTypedSlots.offset(admission.holdingSlot);
    return chunk.active[offset] != 0 && chunk.modes[offset] < request.mode().ordinal();
  }
}
