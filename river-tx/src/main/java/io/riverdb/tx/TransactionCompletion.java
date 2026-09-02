package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Orders terminal lock cleanup, active-set removal, and outcome publication. */
final class TransactionCompletion {
  private final TransactionManager manager;

  TransactionCompletion(TransactionManager owner) { manager = owner; }

  void finish(
      Transaction transaction, TransactionOutcome result,
      TransactionState state, long commitSequence, StatusCode lockOutcome) {
    long id = transaction.transactionId();
    long generation = transaction.transactionGeneration();
    manager.locks.lifecycle.complete(id, generation, lockOutcome);
    manager.removeActive(id);
    transaction.transition(state, commitSequence, true);
    result.set(manager.databaseHigh, manager.databaseLow, id, state, commitSequence);
  }

  StatusCode abortFrozenForConflict(
      Transaction transaction, TransactionOutcome result) {
    result.reset();
    transaction.transition(TransactionState.ABORTING, 0, false);
    finish(transaction, result, TransactionState.ABORTED, 0, StatusCode.CONFLICT);
    return StatusCode.CONFLICT;
  }
}
