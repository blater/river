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

  /** Completes a validated group in phases while the manager retains its snapshot barrier. */
  void finishCommittedGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      long[] commitSequences,
      int count,
      TransactionGroupCompletionTimings timings) {
    timings.reset();
    long started = System.nanoTime();
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      manager.locks.lifecycle.complete(
          transaction.transactionId(),
          transaction.transactionGeneration(),
          StatusCode.CANCELLED,
          timings);
    }
    long released = System.nanoTime();
    for (int index = 0; index < count; index++) {
      manager.removeActive(transactions[index].transactionId());
    }
    long removed = System.nanoTime();
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      long transactionId = transaction.transactionId();
      transaction.transition(
          TransactionState.COMMITTED, commitSequences[index], true);
      results[index].set(
          manager.databaseHigh,
          manager.databaseLow,
          transactionId,
          TransactionState.COMMITTED,
          commitSequences[index]);
    }
    long published = System.nanoTime();
    timings.set(released - started, removed - released, published - removed);
  }

  StatusCode abortFrozenForConflict(
      Transaction transaction, TransactionOutcome result) {
    result.reset();
    transaction.transition(TransactionState.ABORTING, 0, false);
    finish(transaction, result, TransactionState.ABORTED, 0, StatusCode.CONFLICT);
    return StatusCode.CONFLICT;
  }
}
