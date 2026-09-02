package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockToken;

/** Immediate exact-holding admission with the shared reversible index ticket. */
final class LockExactHoldingAdmission {
  private final LockExactTable table;
  private final LockExactAdmissionController admission;

  LockExactHoldingAdmission(LockExactTable owner, LockExactAdmissionController controller) {
    table = owner;
    admission = controller;
  }

  StatusCode create(
      long id, long generation, long startOrder,
      long resource, LockRequest request, LockToken token) {
    StatusCode graphStatus = table.deadlocks.canAdmit(id, generation, startOrder, false);
    if (!graphStatus.isOk()) return graphStatus;
    if (table.nextCapability <= 0 || table.nextCapability == Long.MAX_VALUE
        || table.nextReference <= 0 || table.nextReference == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    table.admission.reset();
    long transaction = table.state.directory.transaction(id, generation);
    boolean newResource = resource < 0;
    boolean newTransaction = transaction < 0;
    StatusCode status;
    if (newResource) {
      status = table.state.resources.reserve(table.admission.resource);
      if (!status.isOk()) return admission.fail(status);
      resource = table.admission.resource.slot;
      status = table.state.resources.prepareTuple(resource, request);
      if (!status.isOk()) return admission.fail(status);
    }
    if (newTransaction) {
      status = table.state.transactions.reserve(table.admission.transaction);
      if (!status.isOk()) return admission.fail(status);
      transaction = table.admission.transaction.slot;
    }
    status = table.state.holdings.reserve(table.admission.holding);
    if (!status.isOk()) return admission.fail(status);
    long holding = table.admission.holding.slot;
    status = admission.indexes.reserve(newResource, newTransaction, true,
        resource, transaction, holding, -1, id, generation, 0, 0, request);
    if (!status.isOk()) return admission.fail(status);
    admission.initialize(
        resource, transaction, id, generation, startOrder,
        request, newResource, newTransaction);
    table.state.initializeHolding(
        holding, resource, transaction, request.mode(), table.nextCapability, 1);
    status = table.holdingLifecycle.issueToken(holding, table.nextReference, token, false);
    if (!status.isOk()) return admission.fail(status);
    admission.indexes.commit(newResource, newTransaction, true,
        resource, transaction, holding, -1, id, generation, 0, 0, request);
    table.state.linkHolding(resource, transaction, holding);
    admission.commitSlots();
    table.nextCapability++;
    table.nextReference++;
    table.holdingCount++;
    return StatusCode.OK;
  }
}
