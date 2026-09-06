package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.lock.LockMode;

/** Handles session reads and the automatic version-maintenance policy. */
final class IndexedTransactionReadAccess {
  private final IndexedTransactionSession session;
  private final IndexedVersionedRowResult visible = new IndexedVersionedRowResult();
  private final HeapRowResult candidateRow = new HeapRowResult();

  IndexedTransactionReadAccess(IndexedTransactionSession session) {
    this.session = session;
  }

  StatusCode maintainVersions() {
    IndexedVacuum automaticVacuum = session.automaticVacuum();
    StatusCode admission = session.table().transactionAdmissionStatus();
    if (admission.isOk()) return StatusCode.OK;
    if (admission != StatusCode.RETRY) return admission;
    if (session.manager().activeTransactionCount() != 0) {
      return automaticVacuum.deferAutomatic(true);
    }
    StatusCode status = automaticVacuum.runAutomatic(
        session.maintenanceOutcome(), true);
    return status.isOk() ? session.table().transactionAdmissionStatus() : status;
  }

  StatusCode fetchByKey(long space, long key, HeapRowResult result) {
    if (session.transaction().state() != io.riverdb.tx.api.TransactionState.ACTIVE
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = session.pendingMutations().count() - 1; index >= 0; index--) {
      if (session.pendingMutations().spaceAt(index) == space
          && session.pendingMutations().keyAt(index) == key) {
        int operation = session.pendingMutations().operationAt(index);
        if (operation == IndexedWalCodec.MUTATION_DELETE
            || operation == IndexedTransactionSession.MUTATION_NONE) {
          result.reset();
          return StatusCode.CONFLICT;
        }
        session.pendingMutations().setRowResult(index, result);
        return StatusCode.OK;
      }
    }
    if (session.transaction().isolationLevel() == IsolationLevel.SERIALIZABLE) {
      StatusCode status = session.protectKey(space, key, LockMode.SHARED);
      if (status.isOk()) status = session.refreshSerializableAfterProtection();
      if (!status.isOk()) return status;
    }
    if (session.transaction().isolationLevel() == IsolationLevel.READ_COMMITTED
        && !session.statementActive()) {
      StatusCode status = session.manager().refreshReadCommitted(
          session.transaction(), session.table());
      if (!status.isOk()) return status;
    }
    StatusCode status = session.table().fetchVersionedByKeyAt(
        session.transaction().snapshot().visibleCommitSequence(), space, key, result, visible);
    session.observeCommit(visible.observedCommitSequence());
    return status;
  }

  StatusCode fetchCandidateByKey(
      long space, long key, LockMode protectionMode, IndexedRowCandidate result) {
    if (result == null
        || protectionMode != LockMode.SHARED && protectionMode != LockMode.UPDATE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (session.transaction().state() != io.riverdb.tx.api.TransactionState.ACTIVE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int pending = session.pendingMutations().findLatestIndex(space, key);
    if (pending >= 0) {
      int operation = session.pendingMutations().operationAt(pending);
      if (operation == IndexedWalCodec.MUTATION_DELETE
          || operation == IndexedTransactionSession.MUTATION_NONE) {
        return StatusCode.CONFLICT;
      }
      session.pendingMutations().setRowResult(pending, result.row());
      result.setPending(
          session, session.transaction().transactionGeneration(), space, key,
          session.pendingMutations().previousRowIdAt(pending), pending);
      return StatusCode.OK;
    }
    if (session.transaction().isolationLevel() == IsolationLevel.SERIALIZABLE) {
      StatusCode status = session.protectKey(space, key, protectionMode);
      if (status.isOk()) status = session.refreshSerializableAfterProtection();
      if (!status.isOk()) return status;
    }
    if (session.transaction().isolationLevel() == IsolationLevel.READ_COMMITTED
        && !session.statementActive()) {
      StatusCode status = session.manager().refreshReadCommitted(
          session.transaction(), session.table());
      if (!status.isOk()) return status;
    }
    StatusCode status = session.table().fetchVersionedByKeyAt(
        session.transaction().snapshot().visibleCommitSequence(), space, key, candidateRow, visible);
    session.observeCommit(visible.observedCommitSequence());
    if (status.isOk()) {
      result.row().copyFrom(candidateRow);
      result.setCommitted(
          session, session.transaction().transactionGeneration(),
          space, key, visible.versionRowId());
    }
    return status;
  }
}
