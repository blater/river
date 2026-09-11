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

  void finishGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      TransactionState state,
      StatusCode lockOutcome) {
    for (int index = 0; index < count; index++) {
      finish(transactions[index], results[index], state, 0, lockOutcome);
    }
  }

  private boolean validFailureGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure,
      TransactionState requiredState) {
    return validFailureGroup(
        transactions, results, count, failure, requiredState, requiredState);
  }

  private boolean validAcceptedFailureGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure) {
    return validFailureGroup(
        transactions, results, count, failure,
        TransactionState.PREPARED, TransactionState.COMMITTING);
  }

  private boolean validFailureGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure,
      TransactionState firstState,
      TransactionState secondState) {
    if (failure == null
        || failure.isOk()
        || transactions == null
        || results == null
        || count <= 0
        || count > transactions.length
        || count > results.length) {
      return false;
    }
    return validGroupMembers(transactions, results, count, firstState, secondState);
  }

  private boolean validGroupMembers(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      TransactionState requiredState) {
    return validGroupMembers(transactions, results, count, requiredState, requiredState);
  }

  private boolean validGroupMembers(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      TransactionState firstState,
      TransactionState secondState) {
    for (int index = 0; index < count; index++) {
      Transaction transaction = transactions[index];
      if (transaction == null
          || !transaction.isOwnedBy(manager)
          || transaction.state() != firstState && transaction.state() != secondState
          || results[index] == null) {
        return false;
      }
      for (int previous = 0; previous < index; previous++) {
        if (transactions[previous] == transaction
            || results[previous] == results[index]) {
          return false;
        }
      }
    }
    return true;
  }

  boolean validCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      long[] commitSequences,
      int count) {
    if (transactions == null
        || results == null
        || commitSequences == null
        || count <= 0
        || count > transactions.length
        || count > results.length
        || count > commitSequences.length) {
      return false;
    }
    if (!validGroupMembers(
        transactions, results, count, TransactionState.COMMITTING)) return false;
    for (int index = 0; index < count; index++) results[index].reset();
    return true;
  }

  StatusCode failCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure) {
    if (!validFailureGroup(
        transactions, results, count, failure, TransactionState.COMMITTING)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TransactionState state = indeterminate(failure)
        ? TransactionState.INDETERMINATE : TransactionState.ABORTED;
    finishGroup(transactions, results, count, state, failure);
    return StatusCode.OK;
  }

  StatusCode abortPreparedCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure) {
    if (!validFailureGroup(
        transactions, results, count, failure, TransactionState.PREPARED)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    finishGroup(transactions, results, count, TransactionState.ABORTED, failure);
    return StatusCode.OK;
  }

  StatusCode failForcedCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure) {
    if (!validFailureGroup(
        transactions, results, count, failure, TransactionState.COMMITTING)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    finishGroup(transactions, results, count, TransactionState.INDETERMINATE, failure);
    return StatusCode.OK;
  }

  StatusCode terminalizeAcceptedCommitGroup(
      Transaction[] transactions,
      TransactionOutcome[] results,
      int count,
      StatusCode failure) {
    if (!validAcceptedFailureGroup(transactions, results, count, failure)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    finishGroup(transactions, results, count, TransactionState.INDETERMINATE, failure);
    return StatusCode.OK;
  }

  static boolean indeterminate(StatusCode status) {
    return status == StatusCode.IO_FAILURE
        || status == StatusCode.FENCED
        || status == StatusCode.CORRUPTION
        || status == StatusCode.INVARIANT_BROKEN;
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
