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
    table.blockCausality.entered();
    table.scheduler.schedule(affectedResource, affectedTransaction);
    if (table.state.requests.occupied(admittedRequest)) {
      LockExactRequestStore.Chunk requests = table.state.requests.record(admittedRequest);
      int offset = LockTypedSlots.offset(admittedRequest);
      if (LockExactTable.WAIT_STATES[requests.states[offset]] == LockWaitState.QUEUED) {
        requests.blockedAtNanos[offset] = blockedClockNanos;
        requests.actuallyBlocked[offset] = 1;
        table.waitCounters.blocked();
        if (table.blockCausality.active()) {
          table.scheduler.recordActualBlock(admittedRequest);
        }
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
    locateExistingSlots(a, id, generation, request);
    StatusCode status = reserveResource(a, request);
    if (!status.isOk()) return status;
    status = reserveTransaction(a);
    if (!status.isOk()) return status;
    status = reserveRequest(a);
    if (!status.isOk()) return status;
    return reserveHolding(a);
  }

  private void locateExistingSlots(
      LockExactAdmission admission, long id, long generation, LockRequest request) {
    admission.resourceSlot = table.state.directory.resource(request);
    admission.transactionSlot = table.state.directory.transaction(id, generation);
    admission.holdingSlot = admission.resourceSlot < 0 ? -1
        : table.state.directory.holding(admission.resourceSlot, id, generation);
    admission.newResource = admission.resourceSlot < 0;
    admission.newTransaction = admission.transactionSlot < 0;
  }

  private StatusCode reserveResource(LockExactAdmission admission, LockRequest request) {
    if (!admission.newResource) return StatusCode.OK;
    StatusCode status = table.state.resources.reserve(admission.resource);
    if (!status.isOk()) return status;
    admission.resourceSlot = admission.resource.slot;
    return table.state.resources.prepareTuple(admission.resourceSlot, request);
  }

  private StatusCode reserveTransaction(LockExactAdmission admission) {
    if (!admission.newTransaction) return StatusCode.OK;
    StatusCode status = table.state.transactions.reserve(admission.transaction);
    if (!status.isOk()) return status;
    admission.transactionSlot = admission.transaction.slot;
    return StatusCode.OK;
  }

  private StatusCode reserveRequest(LockExactAdmission admission) {
    StatusCode status = table.state.requests.reserve(admission.request);
    if (status.isOk()) admission.requestSlot = admission.request.slot;
    return status;
  }

  private StatusCode reserveHolding(LockExactAdmission admission) {
    admission.newHolding = admission.holdingSlot < 0;
    if (admission.newHolding) return reserveNewHolding(admission);
    return references(admission.holdingSlot) == Long.MAX_VALUE
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  private StatusCode reserveNewHolding(LockExactAdmission admission) {
    if (table.nextCapability <= 0 || table.nextCapability == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = table.state.holdings.reserve(admission.holding);
    if (status.isOk()) admission.holdingSlot = admission.holding.slot;
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
