package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Orders terminal lock cleanup, active-set removal, and outcome publication. */
final class TransactionCompletion {
  private final TransactionManager manager;
  private int publishedPending;

  TransactionCompletion(TransactionManager owner) { manager = owner; }

  int publishedPending() { return publishedPending; }

  void finish(
      Transaction transaction, TransactionOutcome result,
      TransactionState state, long commitSequence, StatusCode lockOutcome) {
    long id = transaction.transactionId();
    long generation = transaction.transactionGeneration();
    if (transaction.state() == TransactionState.COMMITTING && transaction.commitSequence() > 0) {
      publishedPending--;
    } else {
      manager.locks.lifecycle.complete(id, generation, lockOutcome);
      manager.removeActive(id);
    }
    transaction.transition(state, commitSequence, true);
    result.set(manager.databaseHigh, manager.databaseLow, id, state, commitSequence);
  }

  /** Completes a validated group in phases while the manager retains its snapshot barrier. */
  void publishCommittedGroup(
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
      // Visibility and lock ownership transfer now; the handle remains pending durability.
      transaction.transition(TransactionState.COMMITTING, commitSequences[index], false);
      publishedPending++;
    }
    timings.set(released - started, removed - released, 0);
  }

  StatusCode completePublishedGroup(
      Transaction[] transactions, TransactionOutcome[] results, int count) {
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      if (transaction == null || !transaction.isOwnedBy(manager)
          || transaction.state() != TransactionState.COMMITTING
          || transaction.commitSequence() <= 0 || results[index] == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      finish(transaction, results[index], TransactionState.COMMITTED,
          transaction.commitSequence(), StatusCode.CANCELLED);
    }
    return StatusCode.OK;
  }

  StatusCode abortFrozenForConflict(
      Transaction transaction, TransactionOutcome result) {
    result.reset();
    transaction.transition(TransactionState.ABORTING, 0, false);
    finish(transaction, result, TransactionState.ABORTED, 0, StatusCode.CONFLICT);
    return StatusCode.CONFLICT;
  }
}
