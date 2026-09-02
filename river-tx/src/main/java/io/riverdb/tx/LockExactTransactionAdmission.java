package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Reversible activation of transaction identity and generic diagnostic context. */
final class LockExactTransactionAdmission {
  private final LockExactTable table;
  private final LockSlotReservation reservation = new LockSlotReservation();

  LockExactTransactionAdmission(LockExactTable owner) { table = owner; }

  StatusCode activate(
      long id, long generation, long startOrder, long diagnosticTag, long metricsEpoch) {
    StatusCode status = table.deadlocks.canAdmit(id, generation, startOrder, false);
    if (!status.isOk()) return status;
    if (table.state.directory.transaction(id, generation) >= 0) return StatusCode.CONFLICT;
    status = table.state.transactions.reserve(reservation);
    if (!status.isOk()) return status;
    long transaction = reservation.slot;
    status = table.state.directory.transactionIndex.reserve(
        transaction, LockExactDirectory.transactionHash(id, generation));
    if (!status.isOk()) {
      table.state.transactions.rollback(reservation);
      return status;
    }
    table.state.initializeTransaction(transaction, id, generation);
    table.deadlocks.initializeTransaction(transaction, startOrder);
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    transactions.diagnosticTags[offset] = diagnosticTag;
    transactions.metricsEpochs[offset] = metricsEpoch;
    transactions.transactionActive[offset] = 1;
    table.state.directory.transactionIndex.add(
        transaction, LockExactDirectory.transactionHash(id, generation));
    table.state.transactions.commit(reservation);
    return StatusCode.OK;
  }
}
