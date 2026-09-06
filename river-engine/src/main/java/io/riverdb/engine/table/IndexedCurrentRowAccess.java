package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.lock.LockMode;

/** Discovers one lock-protected current row from a snapshot candidate or current identity. */
final class IndexedCurrentRowAccess {
  private final IndexedTransactionSession session;
  private final IndexedVersionedRowResult successor = new IndexedVersionedRowResult();
  private final HeapRowResult successorRow = new HeapRowResult();
  private final IndexedRowCandidate scanCandidate = new IndexedRowCandidate();
  private final IndexedRowCandidate keyCandidate = new IndexedRowCandidate();
  private final IndexedLockedRow keyCurrent = new IndexedLockedRow();

  IndexedCurrentRowAccess(IndexedTransactionSession owner) { session = owner; }

  StatusCode lockCurrent(IndexedRowCandidate candidate, IndexedLockedRow result) {
    long generation = session.transaction().transactionGeneration();
    if (candidate == null || !candidate.isOwnedBy(session, generation)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = acquire(candidate.keySpace(), candidate.key(), result);
    if (!status.isOk()) return status;
    int pending = session.pendingMutations().findLatestIndex(
        candidate.keySpace(), candidate.key());
    if (candidate.isPending()) {
      status = pending == candidate.pendingIndex()
          ? publishPending(
              result, generation, candidate.keySpace(), candidate.key(),
              candidate.versionRowId(), pending)
          : StatusCode.CONFLICT;
      return status.isOk() ? status : release(result, status);
    }
    if (pending >= 0 || candidate.versionRowId() <= 0) {
      return release(result, StatusCode.CONFLICT);
    }
    status = session.table().fetchCurrentSuccessor(
        candidate.keySpace(), candidate.key(), candidate.versionRowId(), successorRow, successor);
    session.observeCommit(successor.observedCommitSequence());
    if (!status.isOk()) return release(result, status);
    return publishCommitted(
        result, generation, candidate.keySpace(), candidate.key(), candidate.versionRowId());
  }

  private StatusCode release(IndexedLockedRow result, StatusCode original) {
    StatusCode released = session.lockWait().release(session.transaction(), result.lock());
    StatusCode reset = result.reset();
    if (!released.isOk()) return released;
    return reset.isOk() ? original : reset;
  }

  StatusCode lockCurrent(IndexedScanResult scanned, IndexedLockedRow result) {
    if (scanned == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    long generation = session.transaction().transactionGeneration();
    if (!scanned.isOwnedBy(session, generation)) return StatusCode.INVALID_EXTERNAL_INPUT;
    scanCandidate.reset();
    scanCandidate.row().copyFrom(scanned.row());
    if (scanned.isPending()) {
      scanCandidate.setPending(
          session, generation, scanned.keySpace(), scanned.key(),
          scanned.versionRowId(), scanned.pendingIndex());
    } else {
      scanCandidate.setCommitted(
          session, generation, scanned.keySpace(), scanned.key(), scanned.versionRowId());
    }
    return lockCurrent(scanCandidate, result);
  }

  StatusCode lockCurrentKey(long space, long key, HeapRowResult result) {
    if (result == null || session.transaction().state() != TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = resetCurrentKey();
    keyCandidate.reset();
    if (status.isOk()) status = session.fetchCandidateByKey(
        space, key, LockMode.UPDATE, keyCandidate);
    if (status.isOk()) status = lockCurrent(keyCandidate, keyCurrent);
    if (status.isOk()) result.copyFrom(keyCurrent.row());
    return status;
  }

  StatusCode lockCurrentKeyCurrent(long space, long key, HeapRowResult result) {
    if (result == null || session.transaction().state() != TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = resetCurrentKey();
    if (status.isOk()) status = lockCurrentKeyCurrent(space, key, keyCurrent);
    if (status.isOk()) result.copyFrom(keyCurrent.row());
    return status;
  }

  StatusCode lockCurrentKeyCurrent(long space, long key, IndexedLockedRow result) {
    long generation = session.transaction().transactionGeneration();
    StatusCode status = acquire(space, key, result);
    if (!status.isOk()) return status;
    int pending = session.pendingMutations().findLatestIndex(space, key);
    if (pending >= 0) {
      status = publishPending(
          result, generation, space, key,
          session.pendingMutations().previousRowIdAt(pending), pending);
      return status.isOk() ? status : release(result, status);
    }
    status = session.table().fetchCurrentByKey(space, key, successorRow, successor);
    session.observeCommit(successor.observedCommitSequence());
    if (!status.isOk()) return release(result, status);
    return publishCommitted(
        result, generation, space, key, successor.versionRowId());
  }

  StatusCode retainCurrentKey() {
    return keyCurrent.isAvailable()
        ? session.retainLocked(keyCurrent) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode releaseCurrentKey() {
    return keyCurrent.isAvailable()
        ? session.releaseLocked(keyCurrent) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode resetCurrentKey() {
    return keyCurrent.isAvailable()
        ? StatusCode.CONFLICT : keyCurrent.reset();
  }

  private StatusCode acquire(long space, long key, IndexedLockedRow result) {
    if (result == null || session.transaction().state() != TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = result.reset();
    return status.isOk() ? session.lockWait().acquireBorrowedKey(
        session.transaction(), space, key, LockMode.EXCLUSIVE, result.lock()) : status;
  }

  private StatusCode publishPending(
      IndexedLockedRow result, long generation, long space, long key,
      long sourceVersionRowId, int pending) {
    int operation = session.pendingMutations().operationAt(pending);
    if (operation == IndexedWalCodec.MUTATION_DELETE
        || operation == IndexedTransactionSession.MUTATION_NONE) {
      return StatusCode.CONFLICT;
    }
    session.pendingMutations().setRowResult(pending, result.row());
    result.set(
        session, generation, space, key,
        sourceVersionRowId, sourceVersionRowId, pending);
    return StatusCode.OK;
  }

  private StatusCode publishCommitted(
      IndexedLockedRow result, long generation, long space, long key,
      long sourceVersionRowId) {
    result.row().copyFrom(successorRow);
    result.set(
        session, generation, space, key,
        sourceVersionRowId, successor.versionRowId(), -1);
    return StatusCode.OK;
  }

}
