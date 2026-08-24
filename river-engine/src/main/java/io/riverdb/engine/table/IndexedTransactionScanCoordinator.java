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
      int lowerSpace,
      long lowerKey,
      int upperSpace,
      long upperKey,
      IndexedScanCursor cursor) {
    if (cursor == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (session.transaction().state() != io.riverdb.tx.api.TransactionState.ACTIVE) {
      return StatusCode.CONFLICT;
    }
    if (session.activeScanCount() >= session.activeScanCapacity()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (session.transaction().isolationLevel()
        == io.riverdb.tx.api.IsolationLevel.READ_COMMITTED
        && !session.statementActive()
        && session.activeScanCount() == 0) {
      StatusCode status = session.manager().refreshReadCommitted(
          session.transaction(), session.table());
      if (!status.isOk()) return status;
    }
    StatusCode status = session.table().beginScan(
        session.transaction().snapshot().visibleCommitSequence(),
        lowerSpace, lowerKey, upperSpace, upperKey, cursor);
    if (status.isOk()) status = cursor.attach(session);
    if (status.isOk()
        && session.transaction().isolationLevel()
            == io.riverdb.tx.api.IsolationLevel.SERIALIZABLE) {
      status = session.acquireSharedRangeForScan(
          lowerSpace, lowerKey, upperSpace, upperKey);
    }
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
      if (!cursor.hasCommittedLookahead() && !cursor.committedExhausted()) {
        StatusCode status = session.table().nextScan(
            cursor, cursor.committedLookahead());
        if (status.isOk()) {
          cursor.setCommittedLookahead(true);
        } else if (status == StatusCode.CONFLICT) {
          cursor.setCommittedExhausted();
        } else {
          return status;
        }
      }
      int pendingIndex = session.pendingMutations().nextIndex(cursor);
      if (pendingIndex < 0 && !cursor.hasCommittedLookahead()) {
        return StatusCode.CONFLICT;
      }
      boolean returnPending = pendingIndex >= 0
          && (!cursor.hasCommittedLookahead()
              || OrderedKey.compare(
                  session.pendingMutations().spaceAt(pendingIndex),
                  session.pendingMutations().keyAt(pendingIndex),
                  cursor.committedLookahead().keySpace(),
                  cursor.committedLookahead().key()) <= 0);
      if (returnPending) {
        int pendingSpace = session.pendingMutations().spaceAt(pendingIndex);
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
            || operation == IndexedTransactionSession.MUTATION_NONE) {
          continue;
        }
        session.pendingMutations().setRowResult(pendingIndex, result.row());
        result.set(pendingSpace, pendingKey);
        return StatusCode.OK;
      }
      result.copyFrom(cursor.committedLookahead());
      cursor.setCommittedLookahead(false);
      cursor.returned(result.keySpace(), result.key());
      return StatusCode.OK;
    }
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
