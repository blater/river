package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Closes one named compound admission in strict reverse rollback order. */
final class LockExactAdmissionTicket {
  private final LockExactTable table;

  LockExactAdmissionTicket(LockExactTable owner) { table = owner; }

  StatusCode rollback(StatusCode status) {
    if ((table.admission.indexGrowth & 8) != 0) {
      table.state.directory.laneIndex.rollbackReservation();
    }
    if ((table.admission.indexGrowth & 4) != 0) {
      table.state.directory.holdingIndex.rollbackReservation();
    }
    if ((table.admission.indexGrowth & 2) != 0) {
      table.state.directory.transactionIndex.rollbackReservation();
    }
    if ((table.admission.indexGrowth & 16) != 0) {
      table.state.intervals.rollbackReservation();
    }
    if ((table.admission.indexGrowth & 1) != 0) {
      table.state.directory.resourceIndex.rollbackReservation();
    }
    table.state.holdings.rollback(table.admission.holding);
    table.state.requests.rollback(table.admission.request);
    table.state.transactions.rollback(table.admission.transaction);
    table.state.resources.rollback(table.admission.resource);
    table.admission.reset();
    return status;
  }

  void commit() {
    table.state.resources.commit(table.admission.resource);
    table.state.transactions.commit(table.admission.transaction);
    table.state.holdings.commit(table.admission.holding);
    table.state.requests.commit(table.admission.request);
    table.admission.reset();
  }
}
