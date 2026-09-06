package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.TransactionAdmissionSource;
import io.riverdb.tx.TransactionGroupCommitParticipant;
import io.riverdb.tx.TransactionManager;
import io.riverdb.wal.local.LocalWalMetrics;
import java.nio.ByteBuffer;

/** Transaction-facing facade over one authoritative indexed-table store. */
public final class IndexedTable extends IndexedRelationalTableAccess
    implements TransactionAdmissionSource, TransactionGroupCommitParticipant {
  private final IndexedTableStore store;

  private IndexedTable(IndexedTableStore tableStore) {
    super(tableStore);
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

  @Override
  public synchronized StatusCode transactionAdmissionStatus() {
    return store.transactionAdmissionStatus();
  }

  synchronized StatusCode admitDurableVersionOperations(int required) {
    return store.admitDurableVersionOperations(required);
  }

  synchronized StatusCode completeVersionMaintenance() {
    return store.completeVersionMaintenance();
  }

  synchronized StatusCode preflightHybridCommitGroup(
      IndexedPreparedLogicalCommit[] prepared, int count,
      long oldestVisibleCommitSequence) {
    return store.preflightHybridGroup(
        prepared, count, oldestVisibleCommitSequence);
  }

  synchronized StatusCode reserveHybridCommitGroupCapacity(int required) {
    return store.reserveHybridGroupCapacity(required);
  }

  /** Copies indexed commit-path telemetry into caller-owned storage. */
  public StatusCode copyCommitTelemetry(IndexedGroupCommitTelemetry result) {
    return store.copyCommitMetrics(result);
  }

  /** Copies local WAL force telemetry into caller-owned storage. */
  public StatusCode copyWalMetrics(LocalWalMetrics result) {
    return store.copyWalMetrics(result);
  }

  /** Starts one aggregate-only capture at a caller-established quiescent boundary. */
  public StatusCode beginPerformanceCapture() {
    return store.beginPerformanceCapture();
  }

  /** Ends the active capture into caller-owned snapshots. */
  public StatusCode endPerformanceCapture(
      IndexedGroupCommitTelemetry commitResult,
      LocalWalMetrics walResult) {
    return store.endPerformanceCapture(commitResult, walResult);
  }

  public StatusCode cancelPerformanceCapture() {
    return store.cancelPerformanceCapture();
  }

  IndexedGroupCommitMetrics commitMetrics() { return store.commitMetrics(); }

  DatabaseResourceGovernor resourceGovernor() { return store.resourceGovernor(); }

  boolean matches(TransactionManager manager) { return store.matches(manager); }

  synchronized StatusCode appendHybridCommitGroup(
      IndexedPreparedLogicalCommit[] prepared,
      long[] commitSequences,
      long[] committedRows,
      int count) {
    return store.appendHybridGroup(prepared, commitSequences, committedRows, count);
  }

  StatusCode forceHybridCommitGroup() { return store.forceHybridGroup(); }

  synchronized StatusCode completeGroupDurability() {
    StatusCode status = store.completeHybridGroupDurability();
    notifyAll();
    return status;
  }

  /** Waits at a result-delivery boundary, releasing the table monitor while force is pending. */
  public synchronized StatusCode awaitDurability(long visibleSequence) {
    while (!store.failed && store.pendingDurabilitySequence != 0
        && visibleSequence >= store.pendingDurabilitySequence) {
      try {
        wait();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return StatusCode.CANCELLED;
      }
    }
    return store.admission();
  }

  synchronized StatusCode cancelCommitGroup() {
    StatusCode status = store.cancelCommitGroup();
    notifyAll();
    return status;
  }

  synchronized boolean commitGroupDecisionAppended() {
    return store.commitGroupDecisionAppended();
  }

  synchronized boolean commitGroupDurabilityUncertain() {
    return store.commitGroupDurabilityUncertain();
  }

  synchronized StatusCode fenceCommitWriter() {
    StatusCode status = store.fenceCommitWriter();
    notifyAll();
    return status;
  }

  synchronized StatusCode prepareGroupPublication() {
    return store.prepareGroupPublication();
  }

  @Override
  public synchronized StatusCode installPreparedGroup() {
    return store.installPreparedGroupPublication();
  }


  public synchronized StatusCode vacuum(
      long transactionId,
      IndexedVacuumResult result) {
    return store.vacuum(transactionId, result);
  }

  public synchronized StatusCode vacuumPreflight() {
    return store.vacuumPreflight();
  }

  StatusCode admitLogicalRowIds(long objectId, long publishedFloor) {
    return store.admitLogicalRowIds(objectId, publishedFloor);
  }

  StatusCode reserveLogicalRowIds(
      long objectId, int count, IndexedLogicalRowIdReservation result) {
    return store.reserveLogicalRowIds(objectId, count, result);
  }

  public synchronized StatusCode fetchByKey(
      long space, long key, HeapRowResult result) {
    return store.fetchByKey(space, key, result);
  }

  synchronized StatusCode tupleIndexCleanupComplete(
      int cleanupCursor, int cleanupEndPageId) {
    if (cleanupCursor < io.riverdb.storage.btree.BTreeRootPage.FIRST_REUSABLE_PAGE_ID
        || cleanupEndPageId < cleanupCursor || cleanupEndPageId > store.nextPageId()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return cleanupCursor == cleanupEndPageId ? StatusCode.OK : StatusCode.CONFLICT;
  }

  synchronized int nextPageId() { return store.nextPageId(); }

  public synchronized StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      long space,
      long key,
      HeapRowResult result) {
    return store.fetchByKeyAt(visibleCommitSequence, space, key, result);
  }

  synchronized StatusCode fetchVersionedByKeyAt(
      long visibleCommitSequence, long space, long key,
      IndexedVersionedRowResult result) {
    return store.fetchVersionedByKeyAt(visibleCommitSequence, space, key, result);
  }

  synchronized StatusCode fetchCurrentByKey(
      long space, long key, IndexedVersionedRowResult result) {
    return store.fetchVersionedByKeyAt(store.currentCommitSequence(), space, key, result);
  }

  synchronized StatusCode fetchCurrentSuccessor(
      long space, long key, long candidateRowId, IndexedVersionedRowResult result) {
    return store.fetchCurrentSuccessor(space, key, candidateRowId, result);
  }

  synchronized StatusCode probeTuplePrefixAt(
      long visibleCommitSequence, long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return store.probeTuplePrefixAt(
        visibleCommitSequence, ownerObjectId, keyId, schemaId,
        shape, key, offset, length, result);
  }

  synchronized StatusCode probeTuplePrefixAfterAt(
      long visibleCommitSequence, long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, long afterLogicalRowId,
      IndexedTupleProbeResult result) {
    return store.probeTuplePrefixAfterAt(
        visibleCommitSequence, ownerObjectId, keyId, schemaId,
        shape, key, offset, length, afterLogicalRowId, result);
  }

  synchronized StatusCode probeTuplePrefixCurrent(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return store.probeTuplePrefixCurrent(
        ownerObjectId, keyId, schemaId, shape, key, offset, length, result);
  }

  synchronized StatusCode probeTuplePrefixAfterCurrent(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, long afterLogicalRowId,
      IndexedTupleProbeResult result) {
    return store.probeTuplePrefixAfterCurrent(
        ownerObjectId, keyId, schemaId, shape,
        key, offset, length, afterLogicalRowId, result);
  }

  synchronized StatusCode probeTupleBuildingPrefixCurrent(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return store.probeTupleBuildingPrefixCurrent(
        ownerObjectId, keyId, schemaId, privateOwner,
        shape, key, offset, length, result);
  }

  synchronized StatusCode probeTupleBuildingPrefixAfterCurrent(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, long afterLogicalRowId,
      IndexedTupleProbeResult result) {
    return store.probeTupleBuildingPrefixAfterCurrent(
        ownerObjectId, keyId, schemaId, privateOwner,
        shape, key, offset, length, afterLogicalRowId, result);
  }

  synchronized StatusCode beginTupleScanAt(
      long visibleCommitSequence, long ownerObjectId, long keyId, long schemaId,
      long privateOwner,
      io.riverdb.base.tuple.TupleShape shape,
      io.riverdb.storage.btree.TupleBTreeScanBounds bounds,
      IndexedTupleIntentJournal intents, IndexedTupleScanCursor cursor) {
    return store.beginTupleScanAt(
        visibleCommitSequence, ownerObjectId, keyId, schemaId,
        privateOwner, shape, bounds, intents, cursor);
  }

  synchronized StatusCode nextTupleScan(
      IndexedTupleScanCursor cursor, IndexedTupleIntentJournal intents,
      IndexedTupleScanResult result) {
    return store.nextTupleScan(cursor, intents, result);
  }

  synchronized StatusCode closeTupleScan(IndexedTupleScanCursor cursor) {
    return store.closeTupleScan(cursor);
  }

  public synchronized StatusCode beginScan(
      long visibleCommitSequence,
      long lowerSpace,
      long lowerKey,
      long upperSpace,
      long upperKey,
      IndexedScanCursor cursor) {
    if (visibleCommitSequence < 0
        || !OrderedKey.isFiniteSpace(lowerSpace)
        || !(OrderedKey.isFiniteSpace(upperSpace)
            || OrderedKey.isInfinity(upperSpace, upperKey))
        || !OrderedKey.lessThan(lowerSpace, lowerKey, upperSpace, upperKey)
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = store.firstLeafPageIdAt(
        visibleCommitSequence, lowerSpace, lowerKey);
    if (leafPageId <= 0) {
      return store.snapshotLookupStatus();
    }
    return cursor.claim(
        this, visibleCommitSequence,
        lowerSpace, lowerKey, upperSpace, upperKey, leafPageId);
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
      long space,
      long key,
      IndexedMutationTarget result) {
    return store.prepareMutation(visibleCommitSequence, space, key, result);
  }

  public synchronized StatusCode prepareInsert(
      long visibleCommitSequence,
      long space,
      long key,
      IndexedMutationTarget result) {
    return store.prepareInsert(visibleCommitSequence, space, key, result);
  }

  public synchronized long rowCount() {
    return store.rowCount();
  }

  public synchronized int obsoleteVersionCount() {
    return store.obsoleteVersionCount();
  }

  public synchronized long remainingVersionCapacity() {
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
  public long currentCommitSequence() {
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

  public long relationalCompilationCopyBytes() {
    return store.relationalCompilationCopyBytes();
  }

  public StatusCode flush() {
    return store.flush();
  }

  public StatusCode close() {
    return store.close();
  }
}
