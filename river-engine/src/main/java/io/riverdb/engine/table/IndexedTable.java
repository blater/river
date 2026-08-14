package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.CommitSequenceSource;
import io.riverdb.tx.TransactionGroupCommitParticipant;
import java.nio.ByteBuffer;

/** Transaction-facing facade over one authoritative indexed-table store. */
public final class IndexedTable
    implements CommitSequenceSource, TransactionGroupCommitParticipant {
  private final IndexedTableStore store;

  private IndexedTable(IndexedTableStore tableStore) {
    store = tableStore;
  }

  public static StatusCode create(
      IndexedTableStore store,
      IndexedTableOpenResult result) {
    if (store == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    IndexedTable table = new IndexedTable(store);
    StatusCode status = table.store.initialize();
    if (status.isOk()) {
      result.set(table);
    }
    return status;
  }

  public static StatusCode open(
      IndexedTableStore store,
      IndexedTableOpenResult result) {
    if (store == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    IndexedTable table = new IndexedTable(store);
    StatusCode status = table.store.validate();
    if (status.isOk()) {
      result.set(table);
    }
    return status;
  }

  public synchronized StatusCode insert(
      long transactionId,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return store.insert(transactionId, key, row, result);
  }

  public synchronized StatusCode commitInsert(
      long transactionId,
      long key,
      ByteBuffer row,
      IndexedCommitResult result) {
    return store.commitInsert(transactionId, key, row, result);
  }

  public synchronized StatusCode commitInserts(
      long transactionId,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      IndexedCommitResult result) {
    return store.commitInserts(
        transactionId, keys, rows, rowStride, rowLengths, insertCount, result);
  }

  public synchronized StatusCode commitMutations(
      long transactionId,
      int[] operations,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      IndexedCommitResult result) {
    return store.commitMutations(
        transactionId,
        operations,
        keys,
        previousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        result);
  }

  synchronized StatusCode preflightPreparedCommitGroup(
      IndexedTransactionSession[] sessions,
      int count) {
    if (sessions == null || count <= 0 || count > sessions.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = store.beginPreparedInsertGroup();
    for (int index = 0; status.isOk() && index < count; index++) {
      status = store.preflightPreparedWrites(sessions[index].pendingMutations());
    }
    if (status.isOk()) {
      status = store.finishPreparedInsertPreflight(count);
    }
    if (!status.isOk()) {
      StatusCode cancel = store.cancelPreparedInsertPreflight();
      if (!cancel.isOk()) {
        return cancel;
      }
    }
    return status;
  }

  synchronized StatusCode appendPreparedWrites(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return store.appendPreparedWrites(transactionId, commitSequence, mutations, result);
  }

  synchronized StatusCode cancelPreparedInsertGroup() {
    return store.cancelPreparedInsertGroup();
  }

  StatusCode forcePreparedInserts() {
    return store.forcePreparedInserts();
  }

  @Override
  public synchronized StatusCode publishForcedGroup() {
    return store.publishForcedGroup();
  }

  public synchronized StatusCode vacuum(
      long transactionId,
      IndexedVacuumResult result) {
    return store.vacuum(transactionId, result);
  }

  public synchronized StatusCode vacuumPreflight() {
    return store.vacuumPreflight();
  }

  public synchronized StatusCode insertCommitted(
      long transactionId,
      long commitSequence,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return store.insertCommitted(transactionId, commitSequence, key, row, result);
  }

  synchronized StatusCode commitMutations(
      long transactionId,
      PendingMutationBuffer mutations,
      IndexedCommitResult result) {
    return store.commitMutations(transactionId, mutations, result);
  }

  public synchronized StatusCode fetchByKey(long key, HeapRowResult result) {
    return store.fetchByKey(key, result);
  }

  public synchronized StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      long key,
      HeapRowResult result) {
    return store.fetchByKeyAt(visibleCommitSequence, key, result);
  }

  public synchronized StatusCode beginScan(
      long visibleCommitSequence,
      long lowerKey,
      long upperKey,
      IndexedScanCursor cursor) {
    if (visibleCommitSequence < 0
        || lowerKey >= upperKey
        || upperKey == Long.MIN_VALUE
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = store.firstLeafPageId(lowerKey);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return cursor.claim(this, visibleCommitSequence, lowerKey, upperKey, leafPageId);
  }

  public synchronized StatusCode nextScan(
      IndexedScanCursor cursor,
      IndexedScanResult result) {
    if (cursor == null || !cursor.isOwnedBy(this) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return store.nextScan(cursor, result);
  }

  public synchronized StatusCode closeScan(IndexedScanCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    cursor.complete();
    return StatusCode.OK;
  }

  public synchronized StatusCode prepareMutation(
      long visibleCommitSequence,
      long key,
      IndexedMutationTarget result) {
    return store.prepareMutation(visibleCommitSequence, key, result);
  }

  public synchronized StatusCode prepareInsert(
      long visibleCommitSequence,
      long key,
      IndexedMutationTarget result) {
    return store.prepareInsert(visibleCommitSequence, key, result);
  }

  public synchronized int rowCount() {
    return store.rowCount();
  }

  public synchronized int obsoleteVersionCount() {
    return store.obsoleteVersionCount();
  }

  public synchronized int remainingVersionCapacity() {
    return store.remainingVersionCapacity();
  }

  public int rootPageId() {
    return store.rootPageId();
  }

  public int pageCount() {
    return store.pageCount();
  }

  public synchronized int treeHeight() {
    return store.treeHeight();
  }

  public synchronized long visibleCommitSequence() {
    return store.currentCommitSequence();
  }

  @Override
  public synchronized long currentCommitSequence() {
    return store.currentCommitSequence();
  }

  public synchronized long nextCommitSequence() {
    return store.nextCommitSequence();
  }

  public synchronized long nextTransactionId() {
    return store.nextTransactionId();
  }

  public long stagedCopyBytes() {
    return store.stagedCopyBytes();
  }

  public long walCopyBytes() {
    return store.walCopyBytes();
  }

  public StatusCode flush() {
    return store.flush();
  }

  public StatusCode close() {
    return store.close();
  }
}
