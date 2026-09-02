package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Serializes transaction activation, freeze, commit admission, and terminal lock cleanup. */
final class LockTransactionLifecycle {
  private final LockManager manager;

  LockTransactionLifecycle(LockManager owner) { manager = owner; }

  StatusCode activate(Transaction transaction, long databaseHigh, long databaseLow) {
    synchronized (manager) {
      return transaction.activateContext(manager.authority, databaseHigh, databaseLow);
    }
  }

  StatusCode freezeForCommit(Transaction transaction) {
    synchronized (manager) {
      if (!transaction.contextMatches(manager.authority)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      manager.exact.lifecycle.freeze(
          transaction.transactionId(), transaction.transactionGeneration());
      StatusCode status = transaction.freezeContext(manager.authority);
      if (!status.isOk()) return status;
      return commitBlocked(transaction) ? StatusCode.CONFLICT : StatusCode.OK;
    }
  }

  StatusCode freezeForAbort(Transaction transaction) {
    synchronized (manager) {
      if (!transaction.contextMatches(manager.authority)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      manager.exact.lifecycle.freeze(
          transaction.transactionId(), transaction.transactionGeneration());
      return transaction.freezeContext(manager.authority);
    }
  }

  StatusCode freezeGroup(Transaction[] transactions, int count) {
    synchronized (manager) {
      for (int index = 0; index < count; index++) {
        Transaction transaction = transactions[index];
        if (!transaction.contextMatches(manager.authority)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (commitBlocked(transaction)) return StatusCode.CONFLICT;
      }
      for (int index = 0; index < count; index++) {
        Transaction transaction = transactions[index];
        manager.exact.lifecycle.freeze(
            transaction.transactionId(), transaction.transactionGeneration());
        StatusCode status = transaction.freezeContext(manager.authority);
        if (!status.isOk()) return StatusCode.INVARIANT_BROKEN;
      }
      return StatusCode.OK;
    }
  }

  boolean hasCommitBlocker(Transaction transaction) {
    synchronized (manager) {
      return transaction.contextMatches(manager.authority) && commitBlocked(transaction);
    }
  }

  void complete(long id, long generation, StatusCode outcome) {
    synchronized (manager) {
      manager.exact.lifecycle.releaseAll(id, generation, outcome);
    }
  }

  private boolean commitBlocked(Transaction transaction) {
    long id = transaction.transactionId();
    return manager.exact.deadlocked(id, transaction.transactionGeneration())
        || manager.exact.lifecycle.hasPendingRequests(
            id, transaction.transactionGeneration());
  }
}
