package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;

/** Coordinates session-owned scan cursors and merges pending rows with committed rows. */
final class IndexedTransactionScanCoordinator {
  private final IndexedTransactionSession session;

  IndexedTransactionScanCoordinator(IndexedTransactionSession session) {
    this.session = session;
  }

  StatusCode begin(
      long lowerSpace,
      long lowerKey,
      long upperSpace,
      long upperKey,
      IndexedScanCursor cursor) {
    StatusCode admission = admit(cursor);
    if (!admission.isOk()) return admission;
    StatusCode status = open(
        lowerSpace, lowerKey, upperSpace, upperKey, cursor);
    return finishOpen(status, cursor);
  }

  private StatusCode admit(IndexedScanCursor cursor) {
    if (cursor == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return session.transaction().state() != io.riverdb.tx.api.TransactionState.ACTIVE
        ? StatusCode.CONFLICT : session.reserveActiveScan();
  }

  private StatusCode open(
      long lowerSpace,
      long lowerKey,
      long upperSpace,
      long upperKey,
      IndexedScanCursor cursor) {
    StatusCode status = session.transaction().isolationLevel()
        == io.riverdb.tx.api.IsolationLevel.SERIALIZABLE
            ? session.acquireSharedRangeForScan(
                lowerSpace, lowerKey, upperSpace, upperKey)
            : StatusCode.OK;
    if (status.isOk()) status = session.selectScanSnapshot();
    if (status.isOk()) status = session.table().beginScan(
        session.transaction().snapshot().visibleCommitSequence(),
        lowerSpace, lowerKey, upperSpace, upperKey, cursor);
    if (status.isOk()) status = cursor.attach(session);
    return status;
  }

  private StatusCode finishOpen(StatusCode status, IndexedScanCursor cursor) {
    if (!status.isOk() && cursor.isSessionOwnedBy(session)) {
      StatusCode close = session.table().closeScan(cursor);
      if (!close.isOk()) return close;
    }
    if (status.isOk()) {
      session.registerScan(cursor);
      if (session.transaction().isolationLevel()
          == io.riverdb.tx.api.IsolationLevel.SERIALIZABLE) {
        session.markSerializableScan();
      }
    }
    return status;
  }

  StatusCode next(IndexedScanCursor cursor, IndexedScanResult result) {
    if (session.transaction().state() != io.riverdb.tx.api.TransactionState.ACTIVE
        || cursor == null
        || session.findActiveScan(cursor) < 0
        || !cursor.isSessionOwnedBy(session)
        || result == null) {
      return StatusCode.CONFLICT;
    }
    result.reset();
    while (true) {
      StatusCode status = fillLookahead(cursor);
      if (!status.isOk()) return status;
      int pendingIndex = session.pendingMutations().nextIndex(cursor);
      if (pendingIndex < 0 && !cursor.hasCommittedLookahead()) {
        return StatusCode.CONFLICT;
      }
      if (shouldReturnPending(cursor, pendingIndex)) {
        if (publishPending(cursor, result, pendingIndex)) return StatusCode.OK;
      } else return publishCommitted(cursor, result);
    }
  }

  private StatusCode fillLookahead(IndexedScanCursor cursor) {
    if (cursor.hasCommittedLookahead() || cursor.committedExhausted()) {
      return StatusCode.OK;
    }
    StatusCode status = session.table().nextScan(cursor, cursor.committedLookahead());
    if (status.isOk()) cursor.setCommittedLookahead(true);
    else if (status == StatusCode.CONFLICT) {
      cursor.setCommittedExhausted();
      status = StatusCode.OK;
    }
    return status;
  }

  private boolean shouldReturnPending(IndexedScanCursor cursor, int pendingIndex) {
    return pendingIndex >= 0
        && (!cursor.hasCommittedLookahead()
            || OrderedKey.compare(
                session.pendingMutations().spaceAt(pendingIndex),
                session.pendingMutations().keyAt(pendingIndex),
                cursor.committedLookahead().keySpace(),
                cursor.committedLookahead().key()) <= 0);
  }

  private boolean publishPending(
      IndexedScanCursor cursor, IndexedScanResult result, int pendingIndex) {
    long pendingSpace = session.pendingMutations().spaceAt(pendingIndex);
    long pendingKey = session.pendingMutations().keyAt(pendingIndex);
    if (cursor.hasCommittedLookahead()
        && OrderedKey.equal(
            pendingSpace, pendingKey,
            cursor.committedLookahead().keySpace(),
            cursor.committedLookahead().key())) {
      cursor.setCommittedLookahead(false);
    }
    cursor.returned(pendingSpace, pendingKey);
    int operation = session.pendingMutations().operationAt(pendingIndex);
    if (operation == IndexedWalCodec.MUTATION_DELETE
        || operation == IndexedTransactionSession.MUTATION_NONE) return false;
    session.pendingMutations().setRowResult(pendingIndex, result.row());
    result.setPending(
        session, session.transaction().transactionGeneration(),
        pendingSpace, pendingKey,
        session.pendingMutations().previousRowIdAt(pendingIndex), pendingIndex);
    return true;
  }

  private StatusCode publishCommitted(
      IndexedScanCursor cursor, IndexedScanResult result) {
    result.copyFrom(cursor.committedLookahead());
    result.bind(session, session.transaction().transactionGeneration());
    cursor.setCommittedLookahead(false);
    cursor.returned(result.keySpace(), result.key());
    return StatusCode.OK;
  }

  StatusCode close(IndexedScanCursor cursor) {
    int active = session.findActiveScan(cursor);
    if (cursor == null || active < 0 || !cursor.isSessionOwnedBy(session)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.table().closeScan(cursor);
    if (status.isOk()) session.removeScan(active);
    return status;
  }
}
