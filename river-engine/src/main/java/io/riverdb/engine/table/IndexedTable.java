package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.CommitSequenceSource;
import io.riverdb.tx.TransactionGroupCommitParticipant;
import java.nio.ByteBuffer;

/** Transaction-facing facade over one authoritative indexed-table kernel. */
public final class IndexedTable
    implements CommitSequenceSource, TransactionGroupCommitParticipant {
  private final IndexedTableKernel kernel;

  private IndexedTable(IndexedTableStore store) {
    kernel = store.kernel();
  }

  public static StatusCode create(
      IndexedTableStore store,
      IndexedTableOpenResult result) {
    if (store == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    IndexedTable table = new IndexedTable(store);
    StatusCode status = table.kernel.initialize();
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
    StatusCode status = table.kernel.validate();
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
    return kernel.insert(transactionId, key, row, result);
  }

  public synchronized StatusCode commitInsert(
      long transactionId,
      long key,
      ByteBuffer row,
      IndexedCommitResult result) {
    return kernel.commitInsert(transactionId, key, row, result);
  }

  public synchronized StatusCode commitInserts(
      long transactionId,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      IndexedCommitResult result) {
    return kernel.commitInserts(
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
    return kernel.commitMutations(
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
    return kernel.preflightPreparedCommitGroup(sessions, count);
  }

  synchronized StatusCode appendPreparedWrites(
      IndexedTransactionSession session,
      long commitSequence) {
    return kernel.appendPreparedWrites(session, commitSequence);
  }

  synchronized StatusCode cancelPreparedInsertGroup() {
    return kernel.cancelPreparedInsertGroup();
  }

  StatusCode forcePreparedInserts() {
    return kernel.forcePreparedInserts();
  }

  @Override
  public synchronized StatusCode publishForcedGroup() {
    return kernel.publishForcedGroup();
  }

  public synchronized StatusCode vacuum(
      long transactionId,
      IndexedVacuumResult result) {
    return kernel.vacuum(transactionId, result);
  }

  public synchronized StatusCode vacuumPreflight() {
    return kernel.vacuumPreflight();
  }

  public synchronized StatusCode insertCommitted(
      long transactionId,
      long commitSequence,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return kernel.insertCommitted(transactionId, commitSequence, key, row, result);
  }

  public synchronized StatusCode fetchByKey(long key, HeapRowResult result) {
    return kernel.fetchByKey(key, result);
  }

  public synchronized StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      long key,
      HeapRowResult result) {
    return kernel.fetchByKeyAt(visibleCommitSequence, key, result);
  }

  public synchronized StatusCode beginScan(
      long visibleCommitSequence,
      long lowerKey,
      long upperKey,
      IndexedScanCursor cursor) {
    return kernel.beginScan(this, visibleCommitSequence, lowerKey, upperKey, cursor);
  }

  public synchronized StatusCode nextScan(
      IndexedScanCursor cursor,
      IndexedScanResult result) {
    return kernel.nextScan(this, cursor, result);
  }

  public synchronized StatusCode closeScan(IndexedScanCursor cursor) {
    return kernel.closeScan(this, cursor);
  }

  public synchronized StatusCode prepareMutation(
      long visibleCommitSequence,
      long key,
      IndexedMutationTarget result) {
    return kernel.prepareMutation(visibleCommitSequence, key, result);
  }

  public synchronized StatusCode prepareInsert(
      long visibleCommitSequence,
      long key,
      IndexedMutationTarget result) {
    return kernel.prepareInsert(visibleCommitSequence, key, result);
  }

  public synchronized int rowCount() {
    return kernel.rowCount();
  }

  public synchronized int obsoleteVersionCount() {
    return kernel.obsoleteVersionCount();
  }

  public synchronized int remainingVersionCapacity() {
    return kernel.remainingVersionCapacity();
  }

  public int rootPageId() {
    return kernel.rootPageId();
  }

  public int pageCount() {
    return kernel.pageCount();
  }

  public synchronized int treeHeight() {
    return kernel.treeHeight();
  }

  public synchronized long visibleCommitSequence() {
    return kernel.currentCommitSequence();
  }

  @Override
  public synchronized long currentCommitSequence() {
    return kernel.currentCommitSequence();
  }

  public synchronized long nextCommitSequence() {
    return kernel.nextCommitSequence();
  }

  public synchronized long nextTransactionId() {
    return kernel.nextTransactionId();
  }

  public long stagedCopyBytes() {
    return kernel.stagedCopyBytes();
  }

  public long walCopyBytes() {
    return kernel.walCopyBytes();
  }

  public StatusCode flush() {
    return kernel.flush();
  }

  public StatusCode close() {
    return kernel.close();
  }
}
