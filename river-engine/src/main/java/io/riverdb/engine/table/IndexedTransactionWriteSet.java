package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Applies insert, update, and delete requests to a session-owned pending set. */
final class IndexedTransactionWriteSet {
  private final IndexedTransactionSession session;

  IndexedTransactionWriteSet(IndexedTransactionSession session) {
    this.session = session;
  }

  StatusCode insert(long space, long key, ByteBuffer row) {
    if (!validRow(space, row)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (full()) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = PendingMutationAdmission.reserveAndLock(
        session, space, key, row.remaining());
    if (!status.isOk()) return status;
    int pendingIndex = session.findLatestPendingIndex(space, key);
    if (pendingIndex >= 0) {
      int pendingOperation = session.pendingMutations().operationAt(pendingIndex);
      if (pendingOperation != IndexedWalCodec.MUTATION_DELETE
          && IndexedTransactionSession.MUTATION_NONE != pendingOperation) {
        return StatusCode.CONFLICT;
      }
      session.appendPending(
          pendingOperation == IndexedWalCodec.MUTATION_DELETE
              ? IndexedWalCodec.MUTATION_UPDATE
              : IndexedWalCodec.MUTATION_INSERT,
          space, key, session.pendingMutations().previousRowIdAt(pendingIndex), row,
          row.position(), row.remaining(), true);
      return StatusCode.OK;
    }
    status = session.refreshForWrite();
    if (status.isOk()) {
      status = session.table().prepareInsert(
          session.transaction().snapshot().visibleCommitSequence(),
          space, key, session.mutationTarget());
    }
    if (!status.isOk()) return status;
    session.appendPending(
        IndexedWalCodec.MUTATION_INSERT,
        space, key, session.mutationTarget().rowId(), row,
        row.position(), row.remaining(), true);
    return StatusCode.OK;
  }

  StatusCode update(long space, long key, ByteBuffer row) {
    if (!validRow(space, row)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (full()) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = PendingMutationAdmission.reserveAndLock(
        session, space, key, row.remaining());
    if (!status.isOk()) return status;
    int pendingIndex = session.findLatestPendingIndex(space, key);
    if (pendingIndex >= 0) {
      int pendingOperation = session.pendingMutations().operationAt(pendingIndex);
      if (pendingOperation != IndexedWalCodec.MUTATION_INSERT
          && pendingOperation != IndexedWalCodec.MUTATION_UPDATE) {
        return StatusCode.CONFLICT;
      }
      session.appendPending(
          pendingOperation, space, key,
          session.pendingMutations().previousRowIdAt(pendingIndex), row,
          row.position(), row.remaining(), true);
      return StatusCode.OK;
    }
    status = session.refreshForWrite();
    if (status.isOk()) {
      status = session.table().prepareMutation(
          session.transaction().snapshot().visibleCommitSequence(),
          space, key, session.mutationTarget());
    }
    if (!status.isOk()) return status;
    session.appendPending(
        IndexedWalCodec.MUTATION_UPDATE,
        space, key, session.mutationTarget().rowId(), row,
        row.position(), row.remaining(), true);
    return StatusCode.OK;
  }

  StatusCode delete(long space, long key) {
    if (session.transaction().state() != io.riverdb.tx.api.TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (full()) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = PendingMutationAdmission.reserveAndLock(session, space, key, 1);
    if (!status.isOk()) return status;
    int pendingIndex = session.findLatestPendingIndex(space, key);
    if (pendingIndex >= 0) {
      int pendingOperation = session.pendingMutations().operationAt(pendingIndex);
      if (pendingOperation != IndexedWalCodec.MUTATION_INSERT
          && pendingOperation != IndexedWalCodec.MUTATION_UPDATE) {
        return StatusCode.CONFLICT;
      }
      session.appendPendingDeletion(
          pendingOperation == IndexedWalCodec.MUTATION_INSERT
              ? IndexedTransactionSession.MUTATION_NONE
              : IndexedWalCodec.MUTATION_DELETE,
          space, key, session.pendingMutations().previousRowIdAt(pendingIndex));
      return StatusCode.OK;
    }
    status = session.refreshForWrite();
    if (status.isOk()) {
      status = session.table().prepareMutation(
          session.transaction().snapshot().visibleCommitSequence(),
          space, key, session.mutationTarget());
    }
    if (!status.isOk()) return status;
    session.appendPendingDeletion(
        IndexedWalCodec.MUTATION_DELETE,
        space, key, session.mutationTarget().rowId());
    return StatusCode.OK;
  }

  StatusCode updateLocked(IndexedLockedRow target, ByteBuffer row) {
    if (!validLocked(target) || !validRow(target.keySpace(), row)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (full()) return StatusCode.RESOURCE_EXHAUSTED;
    int pending = session.findLatestPendingIndex(target.keySpace(), target.key());
    if (pending != target.pendingIndex()) return StatusCode.CONFLICT;
    int operation = IndexedWalCodec.MUTATION_UPDATE;
    long previousRowId = target.currentVersionRowId();
    if (pending >= 0) {
      operation = session.pendingMutations().operationAt(pending);
      if (operation != IndexedWalCodec.MUTATION_INSERT
          && operation != IndexedWalCodec.MUTATION_UPDATE) {
        return StatusCode.CONFLICT;
      }
      previousRowId = session.pendingMutations().previousRowIdAt(pending);
    }
    StatusCode status = session.reservePending(1, row.remaining());
    if (!status.isOk()) return status;
    status = session.retainLocked(target);
    if (!status.isOk()) return status;
    session.appendPending(
        operation, target.keySpace(), target.key(), previousRowId,
        row, row.position(), row.remaining(), true);
    return StatusCode.OK;
  }

  StatusCode deleteLocked(IndexedLockedRow target) {
    if (!validLocked(target)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (full()) return StatusCode.RESOURCE_EXHAUSTED;
    int pending = session.findLatestPendingIndex(target.keySpace(), target.key());
    if (pending != target.pendingIndex()) return StatusCode.CONFLICT;
    int operation = IndexedWalCodec.MUTATION_DELETE;
    long previousRowId = target.currentVersionRowId();
    if (pending >= 0) {
      int pendingOperation = session.pendingMutations().operationAt(pending);
      if (pendingOperation != IndexedWalCodec.MUTATION_INSERT
          && pendingOperation != IndexedWalCodec.MUTATION_UPDATE) {
        return StatusCode.CONFLICT;
      }
      operation = pendingOperation == IndexedWalCodec.MUTATION_INSERT
          ? IndexedTransactionSession.MUTATION_NONE : IndexedWalCodec.MUTATION_DELETE;
      previousRowId = session.pendingMutations().previousRowIdAt(pending);
    }
    StatusCode status = session.reservePending(1, 1);
    if (!status.isOk()) return status;
    status = session.retainLocked(target);
    if (!status.isOk()) return status;
    session.appendPendingDeletion(
        operation, target.keySpace(), target.key(), previousRowId);
    return StatusCode.OK;
  }

  private boolean validLocked(IndexedLockedRow target) {
    return target != null
        && target.isOwnedBy(session, session.transaction().transactionGeneration())
        && session.transaction().state() == io.riverdb.tx.api.TransactionState.ACTIVE
        && target.lock().isActive();
  }

  private boolean validRow(long space, ByteBuffer row) {
    return session.transaction().state() == io.riverdb.tx.api.TransactionState.ACTIVE
        && OrderedKey.isFiniteSpace(space)
        && row != null
        && row.hasRemaining()
        && row.remaining() <= session.pendingMutations().rowStride();
  }

  private boolean full() {
    return session.pendingMutations().count() >= session.pendingMutations().capacity();
  }
}
