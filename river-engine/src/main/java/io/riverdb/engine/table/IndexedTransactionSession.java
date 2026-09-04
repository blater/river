package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionCommitParticipant;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.lock.LockMode;
import java.nio.ByteBuffer;

/** One reusable transaction/session write set over the first indexed table. */
public final class IndexedTransactionSession implements TransactionCommitParticipant {
  static final int MUTATION_NONE = 0;

  private final TransactionManager manager;
  private final IndexedTable table;
  private final int maximumWriteEntries;
  private final Transaction transaction;
  private final IndexedSessionRegistry sessionRegistry;
  private final IndexedSessionState state;
  private long committedSequence;
  private long copiedWriteSetBytes;
  private boolean statementActive;
  private boolean closed;

  IndexedTransactionSession(IndexedSessionContext context, int maximumRowBytes) {
    manager = context.manager();
    table = context.table();
    maximumWriteEntries = context.maximumWriteEntries();
    transaction = new Transaction(manager.maximumActiveTransactions());
    sessionRegistry = context.registry();
    state = new IndexedSessionState(
        this, manager, transaction, maximumRowBytes, maximumWriteEntries,
        tuplePayloadCapacity(maximumWriteEntries), context.governor(),
        context.groupCommit(), context.vacuum());
  }

  public Transaction transaction() {
    return transaction;
  }

  /** Applies one opaque diagnostic context before begin or updates its active step. */
  public StatusCode configureTransactionDiagnostics(
      long diagnosticTag, long diagnosticStepTag, long metricsEpoch) {
    if (closed) return StatusCode.CLOSED;
    if (!transaction.isActiveHandle()) {
      return transaction.configureDiagnostics(
          diagnosticTag, diagnosticStepTag, metricsEpoch);
    }
    if (transaction.diagnosticTag() != diagnosticTag
        || transaction.metricsEpoch() != metricsEpoch) {
      return StatusCode.CONFLICT;
    }
    // A deadlock victim is rollback-only. Preserve its captured causal tag and allow
    // the terminal ROLLBACK request through without mutating the frozen lock record.
    if (manager.isDeadlockVictim(transaction)) return StatusCode.OK;
    return transaction.updateDiagnosticStep(diagnosticStepTag);
  }

  /** Updates only the active operation tag while retaining attempt and epoch identity. */
  public StatusCode updateTransactionDiagnosticStep(long diagnosticStepTag) {
    if (closed) return StatusCode.CLOSED;
    return transaction.updateDiagnosticStep(diagnosticStepTag);
  }

  public long copiedWriteSetBytes() {
    return copiedWriteSetBytes;
  }

  /** Current write-set position for higher-level transactional metadata coordination. */
  public int pendingMutationCount() {
    return state.pendingMutations.count();
  }

  /** Current tuple-intent position for statement admission and rollback coordination. */
  public int pendingTupleMutationCount() { return state.tupleIntents.mutationCount(); }

  /** Reserves one table-scoped contiguous identity range and journals its commit floor. */
  public StatusCode reserveLogicalRowIds(
      long objectId, int count, IndexedLogicalRowIdReservation result) {
    if (transaction.state() != TransactionState.ACTIVE || result == null || count <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long retained = IndexedWriteWorkspaceAccounting.floor(
        state.pendingMutations, state.tupleIntents, state.tupleLifecycle,
        state.logicalRowFloors, objectId);
    StatusCode status = ensureCurrentWriteWorkspace(retained);
    if (status.isOk()) status = table.admitLogicalRowIds(objectId, 1);
    if (status.isOk()) status = state.logicalRowFloors.record(objectId, 1);
    if (status.isOk()) status = table.reserveLogicalRowIds(objectId, count, result);
    if (status.isOk()) {
      status = state.logicalRowFloors.record(objectId, result.nextLogicalRowId());
    }
    return status;
  }

  /** Preflights tuple delta slots and bytes against the shared transaction mutation budget. */
  public StatusCode preflightTupleMutations(
      int mutations, int descriptors, int payloadBytes) {
    return state.tupleAccess.preflight(mutations, descriptors, payloadBytes);
  }

  /** Preflights scalar rows and tuple deltas against their shared transaction budget. */
  public StatusCode preflightRelationalMutations(
      int[] rowLengths, int start, int scalarRows,
      int tupleMutations, int descriptors, int tuplePayloadBytes) {
    return state.tupleAccess.preflightRelational(
        rowLengths, start, scalarRows,
        tupleMutations, descriptors, tuplePayloadBytes);
  }

  /** Reports whether this transaction already retains the exact tuple descriptor identity. */
  public StatusCode tupleDescriptorStatus(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape) {
    return state.tupleAccess.descriptorStatus(ownerObjectId, keyId, schemaId, shape);
  }

  /** Stages one physical tuple delta; roots and registry generations are derived at commit. */
  public StatusCode appendTupleMutation(
      int operation, long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape, long logicalRowId,
      ByteBuffer key, int offset, int length) {
    return state.tupleAccess.append(
        operation, ownerObjectId, keyId, schemaId, shape,
        logicalRowId, key, offset, length);
  }

  /** Reserves an atomic tuple-index lifecycle batch before its first request is staged. */
  public StatusCode preflightTupleIndexLifecycles(int additional) {
    return state.tupleAccess.preflightLifecycles(additional);
  }

  /** Stages one private empty tuple root; catalog visibility remains a later transaction. */
  public StatusCode stageTupleIndexBuilding(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape) {
    return stageTupleLifecycle(
        IndexedTupleIndexLifecycleBatch.CREATE_BUILDING,
        ownerObjectId, keyId, schemaId, privateOwner, shape);
  }

  /** Stages publication of one previously built and validated empty tuple root. */
  public StatusCode stageTupleIndexReady(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape) {
    return stageTupleLifecycle(
        IndexedTupleIndexLifecycleBatch.PUBLISH_READY,
        ownerObjectId, keyId, schemaId, privateOwner, shape);
  }

  /** Stages one bounded tuple batch while retaining private BUILDING ownership. */
  public StatusCode stageTupleIndexBuildingBatch(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape) {
    return stageTupleLifecycle(
        IndexedTupleIndexLifecycleBatch.APPEND_BUILDING,
        ownerObjectId, keyId, schemaId, privateOwner, shape);
  }

  /** Detaches one BUILDING or READY root and records cleanup ownership. */
  public StatusCode stageTupleIndexDropping(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape) {
    return stageTupleLifecycle(
        IndexedTupleIndexLifecycleBatch.DETACH_DROPPING,
        ownerObjectId, keyId, schemaId, privateOwner, shape);
  }

  /** Reclaims the next bounded page range of one detached tuple index. */
  public StatusCode stageTupleIndexReclaim(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape, int cleanupEndPageId) {
    return stageTupleLifecycle(
        IndexedTupleIndexLifecycleBatch.RECLAIM_DROPPING,
        ownerObjectId, keyId, schemaId, privateOwner, shape, cleanupEndPageId);
  }

  /** Publishes ABSENT after the detached tuple graph has been fully reclaimed. */
  public StatusCode stageTupleIndexAbsent(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape, int cleanupEndPageId) {
    return stageTupleLifecycle(
        IndexedTupleIndexLifecycleBatch.FINISH_DROPPING,
        ownerObjectId, keyId, schemaId, privateOwner, shape, cleanupEndPageId);
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    if (closed) return StatusCode.CLOSED;
    if (transaction.isActiveHandle()
        || hasActiveScans()
        || state.savepoints.active()
        || statementActive) {
      return StatusCode.CONFLICT;
    }
    state.pendingMutations.truncate(0);
    state.tupleIntents.reset();
    state.tupleLifecycle.reset();
    state.logicalRowFloors.reset();
    state.preparedCommit.reset();
    committedSequence = 0;
    statementActive = false;
    StatusCode maintenance = maintainVersions();
    if (!maintenance.isOk()) {
      return maintenance;
    }
    StatusCode status = manager.begin(isolationLevel, table, transaction);
    if (status.isOk()) {
      status = state.resourceAdmission.begin(transaction.transactionId());
      if (!status.isOk()) manager.abort(transaction, state.maintenanceOutcome);
    }
    return status;
  }

  /** Pins one statement snapshot while higher layers perform related storage calls. */
  public StatusCode beginStatement() {
    if (transaction.state() != TransactionState.ACTIVE
        || statementActive
        || hasActiveScans()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = transaction.isolationLevel() == IsolationLevel.READ_COMMITTED
        ? manager.refreshReadCommitted(transaction, table) : StatusCode.OK;
    if (status.isOk()) {
      statementActive = true;
    }
    return status;
  }

  public StatusCode completeStatement() {
    if (!statementActive || hasActiveScans()) {
      return StatusCode.CONFLICT;
    }
    if (transaction.state() != TransactionState.ACTIVE) {
      statementActive = false;
      return transaction.isActiveHandle() ? StatusCode.CONFLICT : StatusCode.OK;
    }
    statementActive = false;
    return StatusCode.OK;
  }

  private StatusCode maintainVersions() {
    return state.readAccess.maintainVersions();
  }

  public StatusCode insert(long space, long key, ByteBuffer row) {
    return state.writeSet.insert(space, key, row);
  }

  public StatusCode update(long space, long key, ByteBuffer row) {
    return state.writeSet.update(space, key, row);
  }

  public StatusCode delete(long space, long key) {
    return state.writeSet.delete(space, key);
  }

  /** Reads one durable tuple-root state through this transaction's current snapshot. */
  public StatusCode readTupleIndexState(long keyId, IndexedTupleIndexState result) {
    return state.tupleAccess.readState(keyId, result);
  }

  /**
   * Probes committed physical entries sharing one complete generic user key.
   *
   * <p>The probe returns {@link StatusCode#RETRY} when this transaction's visible snapshot is not
   * the table's current commit sequence. Pending tuple intents are not overlaid; callers needing
   * read-your-writes must inspect {@link #pendingTupleMutationCount()} and fall back or merge them.
   */
  public StatusCode probeTuplePrefix(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return state.tupleAccess.probePrefix(
        ownerObjectId, keyId, schemaId, shape, key, offset, length, result);
  }

  /** Resolves one SQL-unique tuple through the visible root plus this transaction's intents. */
  public StatusCode resolveTupleUniquePrefix(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return state.tupleAccess.resolveUnique(
        ownerObjectId, keyId, schemaId, shape, key, offset, length, result);
  }

  /** Resolves a stable SQL-unique source mapping under shared or exclusive tuple protection. */
  public StatusCode resolveTupleUniqueSource(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, LockMode mode,
      IndexedTupleProbeResult result) {
    return state.tupleAccess.source.resolve(
        ownerObjectId, keyId, schemaId, shape, key, offset, length, mode, result);
  }

  /** Resolves one current SQL-unique tuple while retaining shared integrity protection. */
  public StatusCode resolveTupleUniqueCurrent(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return state.tupleAccess.current.unique(
        ownerObjectId, keyId, schemaId, shape, key, offset, length, result);
  }

  /** Resolves any tuple sharing a user prefix through the visible root plus this transaction. */
  public StatusCode resolveTupleAnyPrefix(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return resolveTupleAnyPrefixExcept(
        ownerObjectId, keyId, schemaId, shape, key, offset, length, 0, result);
  }

  /** Resolves any matching tuple except one caller-owned logical row. */
  public StatusCode resolveTupleAnyPrefixExcept(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, long excludedRowId,
      IndexedTupleProbeResult result) {
    return state.tupleAccess.resolveAny(
        ownerObjectId, keyId, schemaId, shape, key, offset, length,
        excludedRowId, result);
  }

  /** Resolves any current tuple sharing a user prefix after its integrity lock is retained. */
  public StatusCode resolveTupleAnyPrefixCurrent(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return resolveTupleAnyPrefixCurrentExcept(
        ownerObjectId, keyId, schemaId, shape,
        key, offset, length, 0, result);
  }

  /** Resolves any current matching tuple except one caller-owned logical row. */
  public StatusCode resolveTupleAnyPrefixCurrentExcept(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, long excludedRowId,
      IndexedTupleProbeResult result) {
    return state.tupleAccess.current.any(
        ownerObjectId, keyId, schemaId, shape, key, offset, length,
        excludedRowId, result);
  }

  /** Holds shared lifecycle and ordered tuple-key protection through transaction completion. */
  public StatusCode protectTupleKey(
      long keyId, ByteBuffer key, int offset, int length) {
    return IndexedTupleKeyProtection.protectShared(this, keyId, key, offset, length);
  }

  /** Holds shared lifecycle and exclusive exact-key protection through transaction completion. */
  public StatusCode protectTupleKeyForWrite(
      long keyId, ByteBuffer key, int offset, int length) {
    return IndexedTupleKeyProtection.protectExclusive(this, keyId, key, offset, length);
  }

  /** Reports exact exclusive tuple protection without acquiring or retaining it again. */
  public StatusCode tupleWriteProtectionStatus(
      long keyId, ByteBuffer key, int offset, int length) {
    if (!io.riverdb.format.catalog.CatalogKeyspace.validKeyId(keyId)
        || !IndexedTupleLockKey.valid(key, offset, length)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return state.lockWait.holdsTupleKey(
        transaction, keyId, key,
        IndexedTupleLockKey.userOffset(key, offset, length),
        IndexedTupleLockKey.userLength(key, offset, length),
        LockMode.EXCLUSIVE);
  }

  /** Reports whether this session owns a conditional exact-source tuple guard. */
  public boolean tupleSourceBorrowed() { return state.tupleAccess.source.borrowed(); }

  /** Adopts the conditional exact-source tuple guard through transaction completion. */
  public StatusCode retainTupleSource() { return state.tupleAccess.source.retain(); }

  /** Releases a conditional exact-source tuple guard after current-row rejection. */
  public StatusCode releaseTupleSource() { return state.tupleAccess.source.release(); }

  /** Serializes and validates one SQL-unique user tuple against committed and pending keys. */
  public StatusCode validateTupleUniquePrefix(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, long logicalRowId) {
    return state.tupleAccess.validateUnique(
        ownerObjectId, keyId, schemaId, shape,
        key, offset, length, logicalRowId);
  }

  /** Validates an append-only build key against private BUILDING and pending batch entries. */
  public StatusCode validateTupleBuildingUniquePrefix(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      io.riverdb.base.tuple.TupleShape shape,
      ByteBuffer key, int offset, int length, long logicalRowId) {
    return state.tupleAccess.validateBuilding(
        ownerObjectId, keyId, schemaId, privateOwner, shape,
        key, offset, length, logicalRowId);
  }

  /** Tests the current allocator frontier before staging a detached-root finish. */
  public StatusCode tupleIndexCleanupComplete(
      IndexedTupleIndexState state, int cleanupEndPageId) {
    if (transaction.state() != TransactionState.ACTIVE || state == null
        || state.state()
            != io.riverdb.format.btree.TupleIndexRootRecordCodec.STATE_DROPPING) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return table.tupleIndexCleanupComplete(state.cleanupCursor(), cleanupEndPageId);
  }

  /** Captures the stable terminal page horizon before detaching one private root. */
  public int tupleIndexCleanupHorizon() {
    return transaction.state() == TransactionState.ACTIVE ? table.nextPageId() : 0;
  }

  /** Admits one caller-owned mutation before its lock or staged write. */
  public StatusCode preflightPendingMutation(int rowBytes) {
    if (transaction.state() != TransactionState.ACTIVE) {
      return StatusCode.CONFLICT;
    }
    if (!sharedMutationCapacity(1)) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = ensureWriteEntries(
        1, IndexedWriteWorkspaceAccounting.scalar(
            state.pendingMutations, state.tupleIntents, state.tupleLifecycle,
            state.logicalRowFloors, rowBytes));
    return status.isOk() ? state.pendingMutations.reserve(1, rowBytes) : status;
  }

  /** Admits a complete variable-width mutation group before its first lock or staged write. */
  public StatusCode preflightPendingMutations(
      int[] rowLengths, int start, int additionalRows) {
    if (transaction.state() != TransactionState.ACTIVE) {
      return StatusCode.CONFLICT;
    }
    if (!sharedMutationCapacity(additionalRows)) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = ensureWriteEntries(
        additionalRows, IndexedWriteWorkspaceAccounting.scalar(
            state.pendingMutations, state.tupleIntents, state.tupleLifecycle,
            state.logicalRowFloors,
            rowLengths, start, additionalRows));
    return status.isOk()
        ? state.pendingMutations.reserve(rowLengths, start, additionalRows) : status;
  }

  StatusCode reservePending(int additionalRows, int additionalRowBytes) {
    if (!sharedMutationCapacity(additionalRows)) return StatusCode.RESOURCE_EXHAUSTED;
    long retainedBytes = additionalRows == 1
        ? IndexedWriteWorkspaceAccounting.scalar(
            state.pendingMutations, state.tupleIntents, state.tupleLifecycle,
            state.logicalRowFloors, additionalRowBytes) : -1;
    StatusCode status = ensureWriteEntries(additionalRows, retainedBytes);
    return status.isOk()
        ? state.pendingMutations.reserve(additionalRows, additionalRowBytes) : status;
  }

  StatusCode reservePending(int[] rowLengths, int start, int additionalRows) {
    if (!sharedMutationCapacity(additionalRows)) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = ensureWriteEntries(
        additionalRows, IndexedWriteWorkspaceAccounting.scalar(
            state.pendingMutations, state.tupleIntents, state.tupleLifecycle,
            state.logicalRowFloors,
            rowLengths, start, additionalRows));
    return status.isOk()
        ? state.pendingMutations.reserve(rowLengths, start, additionalRows) : status;
  }

  void appendPendingDeletion(
      int operation, long space, long key, long previousRowId) {
    state.pendingMutations.appendDeletion(operation, space, key, previousRowId);
  }

  void appendPending(
      int operation,
      long space,
      long key,
      long previousRowId,
      ByteBuffer source,
      int sourceStart,
      int rowBytes,
      boolean countCopy) {
    state.pendingMutations.append(
        operation, space, key, previousRowId, source, sourceStart, rowBytes);
    if (countCopy) {
      copiedWriteSetBytes += rowBytes;
    }
  }

  public StatusCode fetchByKey(long space, long key, HeapRowResult result) {
    return state.readAccess.fetchByKey(space, key, result);
  }

  public StatusCode fetchCandidateByKey(
      long space, long key, LockMode protectionMode, IndexedRowCandidate result) {
    return state.readAccess.fetchCandidateByKey(space, key, protectionMode, result);
  }

  /** Locks a selected logical key and returns its current uninterrupted update successor. */
  public StatusCode lockCurrent(
      IndexedRowCandidate candidate, IndexedLockedRow result) {
    return state.currentRows.lockCurrent(candidate, result);
  }

  public StatusCode lockCurrent(
      IndexedScanResult scanned, IndexedLockedRow result) {
    return state.currentRows.lockCurrent(scanned, result);
  }

  /** Locks and discovers one current committed logical row without snapshot candidacy. */
  public StatusCode lockCurrentKeyCurrent(
      long space, long key, IndexedLockedRow result) {
    return state.currentRows.lockCurrentKeyCurrent(space, key, result);
  }

  public StatusCode updateLocked(IndexedLockedRow target, ByteBuffer row) {
    return state.writeSet.updateLocked(target, row);
  }

  public StatusCode deleteLocked(IndexedLockedRow target) {
    return state.writeSet.deleteLocked(target);
  }

  /** Adopts a borrowed current-row guard as a transaction-lifetime lock. */
  public StatusCode retainLocked(IndexedLockedRow target) {
    if (target == null
        || !target.isOwnedBy(this, transaction.transactionGeneration())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = state.lockWait.retain(transaction, target.lock());
    if (status.isOk()) target.consume();
    return status;
  }

  /** Releases a borrowed current-row guard without retaining the row lock. */
  public StatusCode releaseLocked(IndexedLockedRow target) {
    if (target == null
        || !target.isOwnedBy(this, transaction.transactionGeneration())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = state.lockWait.release(transaction, target.lock());
    if (status.isOk()) target.consume();
    return status;
  }

  /** Holds the requested exact key mode through transaction completion. */
  public StatusCode protectKey(long space, long key, LockMode mode) {
    if (transaction.state() != TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)
        || mode == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return state.lockWait.acquireKey(transaction, space, key, mode);
  }

  /** Borrows the current uninterrupted successor of one snapshot-selected logical key. */
  public StatusCode lockCurrentKey(long space, long key, HeapRowResult result) {
    return state.currentRows.lockCurrentKey(space, key, result);
  }

  /** Borrows the current row after acquiring its exclusive key lock before reading it. */
  public StatusCode lockCurrentKeyCurrent(long space, long key, HeapRowResult result) {
    return state.currentRows.lockCurrentKeyCurrent(space, key, result);
  }

  /** Adopts the borrowed current-key guard as transaction-lifetime ownership. */
  public StatusCode retainCurrentKey() {
    return state.currentRows.retainCurrentKey();
  }

  /** Releases a borrowed current-key guard whose current row was rejected. */
  public StatusCode releaseCurrentKey() {
    return state.currentRows.releaseCurrentKey();
  }

  public StatusCode beginScan(
      long lowerSpace,
      long lowerKey,
      long upperSpace,
      long upperKey,
      IndexedScanCursor cursor) {
    return state.scans.begin(lowerSpace, lowerKey, upperSpace, upperKey, cursor);
  }

  public StatusCode nextScan(IndexedScanCursor cursor, IndexedScanResult result) {
    return state.scans.next(cursor, result);
  }

  public StatusCode closeScan(IndexedScanCursor cursor) {
    return state.scans.close(cursor);
  }

  public StatusCode beginTupleScan(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape,
      io.riverdb.storage.btree.TupleBTreeScanBounds bounds,
      LockMode serializableSourceMode,
      IndexedTupleScanCursor cursor) {
    return state.tupleScans.begin(
        ownerObjectId, keyId, schemaId, shape, bounds, serializableSourceMode, cursor);
  }

  public StatusCode nextTupleScan(
      IndexedTupleScanCursor cursor, IndexedTupleScanResult result) {
    return state.tupleScans.next(cursor, result);
  }

  public StatusCode closeTupleScan(IndexedTupleScanCursor cursor) {
    return state.tupleScans.close(cursor);
  }

  public StatusCode createSavepoint(IndexedSavepoint savepoint) {
    return state.savepoints.create(savepoint, hasActiveScans());
  }

  /** Rolls back pending mutations but deliberately retains acquired locks until transaction end. */
  public StatusCode rollbackToSavepoint(IndexedSavepoint savepoint) {
    return state.savepoints.rollback(savepoint, hasActiveScans());
  }

  public StatusCode releaseSavepoint(IndexedSavepoint savepoint) {
    return state.savepoints.release(savepoint);
  }

  public StatusCode cancelLockWait() {
    return state.lockWait.cancel(transaction);
  }

  /** Whether this session was created over the exact supplied database table instance. */
  public boolean belongsTo(IndexedTable owner) {
    return table == owner;
  }

  int activeScanCount() {
    return state.cursors.scalarCount();
  }

  boolean hasActiveScans() { return state.cursors.active(); }

  boolean activeTransaction() { return transaction.state() == TransactionState.ACTIVE; }

  long visibleCommitSequence() { return transaction.snapshot().visibleCommitSequence(); }

  StatusCode selectScanSnapshot() {
    if (hasActiveScans()) return StatusCode.OK;
    if (transaction.isolationLevel() == IsolationLevel.READ_COMMITTED) {
      return statementActive
          ? StatusCode.OK : manager.refreshReadCommitted(transaction, table);
    }
    return transaction.isolationLevel() == IsolationLevel.SERIALIZABLE
        ? manager.refreshSerializableAfterProtection(transaction, table)
        : StatusCode.OK;
  }

  /** Advances strict-2PL visibility only after the caller holds complete read/write protection. */
  StatusCode refreshSerializableAfterProtection() {
    if (transaction.isolationLevel() != IsolationLevel.SERIALIZABLE) {
      return StatusCode.OK;
    }
    return hasActiveScans()
        ? StatusCode.OK : manager.refreshSerializableAfterProtection(transaction, table);
  }

  StatusCode reserveActiveScan() {
    return state.cursors.reserveScalar();
  }

  StatusCode reserveTupleScan() {
    return state.cursors.reserveTuple();
  }

  boolean statementActive() {
    return statementActive;
  }

  TransactionManager manager() {
    return manager;
  }

  IndexedTable table() {
    return table;
  }

  IndexedVacuum automaticVacuum() {
    return state.automaticVacuum;
  }

  TransactionOutcome maintenanceOutcome() {
    return state.maintenanceOutcome;
  }

  void registerScan(IndexedScanCursor cursor) {
    state.cursors.add(cursor);
  }

  void removeScan(int active) {
    state.cursors.removeScalar(active);
  }

  void registerTupleScan(IndexedTupleScanCursor cursor) {
    state.cursors.add(cursor);
  }

  void removeTupleScan(int active) {
    state.cursors.removeTuple(active);
  }

  int findTupleScan(IndexedTupleScanCursor cursor) {
    return state.cursors.find(cursor);
  }

  int findActiveScan(IndexedScanCursor cursor) {
    return state.cursors.find(cursor);
  }

  public StatusCode commit(TransactionOutcome result) {
    if (result == null) {
      table.commitMetrics().recordFailedBefore(StatusCode.INVALID_EXTERNAL_INPUT);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (hasActiveScans() || statementActive) {
      table.commitMetrics().recordFailedBefore(StatusCode.CONFLICT);
      return StatusCode.CONFLICT;
    }
    if (!transaction.isActiveHandle()
        && state.resourceAdmission.active()) {
      return completeTerminalCleanup(StatusCode.OK);
    }
    compactWriteSet();
    if (state.pendingMutations.count() == 0 && state.tupleIntents.mutationCount() == 0
        && !state.tupleLifecycle.active() && state.logicalRowFloors.count() == 0) {
      table.commitMetrics().recordReadOnlyCommit();
      StatusCode status = manager.commitReadOnly(transaction, result);
      return !transaction.isActiveHandle() ? completeTerminalCleanup(status) : status;
    }
    if (state.groupCommit != null) {
      return state.groupCommit.commit(state.groupCommitRequest, result);
    }
    int eligibilityMask = commitGroupEligibilityMask();
    long logicalStarted = System.nanoTime();
    StatusCode logical = prepareLogicalCommit();
    table.commitMetrics().recordStage(
        IndexedCommitPath.DIRECT_COMMIT,
        IndexedCommitStage.LOGICAL_PREPARATION,
        System.nanoTime() - logicalStarted);
    if (!logical.isOk()) {
      table.commitMetrics().recordStageFailure(
          IndexedCommitPath.DIRECT_COMMIT, IndexedCommitStage.LOGICAL_PREPARATION, logical);
      table.commitMetrics().recordFailedBefore(logical);
      return logical;
    }
    table.commitMetrics().recordWriteSubmission(eligibilityMask, false);
    table.commitMetrics().recordDirectCommit(IndexedDirectCommitReason.EXPLICIT_DIRECT_PATH);
    StatusCode status = manager.commit(transaction, this, result);
    if (transaction.state() == TransactionState.ACTIVE) cancelLogicalCommit();
    return completeCoordinatedCommit(status);
  }

  boolean hasCommitWork() {
    return transaction.state() == TransactionState.ACTIVE
        && state.hasHybridWork();
  }

  boolean eligibleForCommitGroup() {
    return commitGroupEligibilityMask() == 0;
  }

  int commitGroupEligibilityMask() {
    int mask = 0;
    if (transaction.state() != TransactionState.ACTIVE) {
      mask |= 1;
    }
    if (hasActiveScans()) {
      mask |= 1 << 1;
    }
    if (state.pendingMutations.count() == 0
        && state.tupleIntents.mutationCount() == 0
        && state.logicalRowFloors.count() == 0) {
      mask |= 1 << 2;
    }
    if (state.tupleLifecycle.active()) {
      mask |= 1 << 3;
    }
    if (manager.hasLockConflict(transaction)) {
      mask |= 1 << 4;
    }
    return mask;
  }

  Transaction groupTransaction() {
    return transaction;
  }

  StatusCode prepareCoordinatedCommit(TransactionOutcome result) {
    return manager.prepareCommit(transaction, result);
  }

  StatusCode prepareLogicalCommit() {
    return state.preparedCommit.prepare();
  }

  void cancelLogicalCommit() { state.preparedCommit.reset(); }

  IndexedPreparedLogicalCommit preparedCommit() { return state.preparedCommit; }

  PendingMutationBuffer pendingMutations() {
    return state.pendingMutations;
  }

  IndexedTupleIntentJournal tupleIntents() { return state.tupleIntents; }

  IndexedLogicalRowIdFloors logicalRowFloors() { return state.logicalRowFloors; }

  IndexedTupleIndexLifecycleBatch tupleLifecycle() { return state.tupleLifecycle; }

  boolean hasTupleIntents() { return state.tupleIntents.mutationCount() > 0; }

  boolean hasHybridWork() { return state.hasHybridWork(); }

  IndexedMutationTarget mutationTarget() {
    return state.mutationTarget;
  }

  IndexedRelationalWalPlan groupWalPlan() { return state.groupWalPlan; }

  void recordGroupPublication(long rowId, long commitSequence) {
    state.commitResult.set(rowId, commitSequence);
    committedSequence = commitSequence;
  }

  StatusCode commitDirect(TransactionOutcome result) {
    return manager.commitPrepared(transaction, this, result);
  }

  StatusCode completeCoordinatedCommit(StatusCode status) {
    return !transaction.isActiveHandle() ? completeTerminalCleanup(status) : status;
  }

  public StatusCode abort(TransactionOutcome result) {
    if (!transaction.isActiveHandle()) {
      statementActive = false;
      if (hasActiveScans()) return StatusCode.CONFLICT;
      return state.resourceAdmission.active()
          ? completeTerminalCleanup(StatusCode.OK) : StatusCode.CONFLICT;
    }
    if (hasActiveScans() || statementActive) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = manager.abort(transaction, result);
    return !transaction.isActiveHandle() ? completeTerminalCleanup(status) : status;
  }

  @Override
  public StatusCode commit(long transactionId) {
    StatusCode status = hasHybridWork()
        ? table.commitHybrid(
            state.preparedCommit,
            manager.oldestVisibleCommitSequence(), state.commitResult)
        : StatusCode.INVALID_EXTERNAL_INPUT;
    committedSequence = status.isOk() ? state.commitResult.commitSequence() : 0;
    return status;
  }

  @Override
  public long committedSequence() {
    return committedSequence;
  }

  private StatusCode completeTerminalCleanup(StatusCode terminal) {
    statementActive = false;
    clearWriteSet();
    StatusCode release = state.resourceAdmission.end();
    return release.isOk() ? terminal : release;
  }

  /** True until both transaction outcome and its physical/accounting cleanup are complete. */
  public boolean transactionLifecycleActive() {
    return transaction.isActiveHandle() || state.resourceAdmission.active();
  }

  /** Releases session-retained workspaces and returns this session to its database owner. */
  public StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    if (transactionLifecycleActive() || hasActiveScans() || statementActive) {
      return StatusCode.CONFLICT;
    }
    state.pendingMutations.release();
    state.tupleIntents.release();
    state.tupleLifecycle.release();
    state.logicalRowFloors.release();
    StatusCode status = state.resourceAdmission.closeSession();
    if (status.isOk()) status = sessionRegistry.release(this);
    if (status.isOk()) closed = true;
    return status;
  }

  private void clearWriteSet() {
    state.preparedCommit.reset();
    clearPendingFrom(0);
    state.tupleIntents.reset();
    state.tupleLifecycle.reset();
    state.logicalRowFloors.reset();
    state.savepoints.clear();
  }

  private void clearPendingFrom(int first) {
    state.pendingMutations.truncate(first);
  }

  boolean sharedMutationCapacity(int additional) {
    return additional > 0
        && state.pendingMutations.count() + state.tupleIntents.mutationCount() + state.tupleLifecycle.count()
            <= maximumWriteEntries - additional;
  }

  private StatusCode ensureWriteEntries(int additional) {
    return ensureWriteEntries(additional, currentRetainedWriteBytes());
  }

  StatusCode ensureWriteEntries(int additional, long retainedWriteBytes) {
    int current = currentWriteEntries();
    if (additional <= 0 || retainedWriteBytes < 0
        || current > Integer.MAX_VALUE - additional) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return state.resourceAdmission.ensureWrite(
        retainedWriteBytes, current + additional, current == 0);
  }

  private long currentRetainedWriteBytes() {
    return IndexedWriteWorkspaceAccounting.current(
        state.pendingMutations, state.tupleIntents, state.tupleLifecycle,
        state.logicalRowFloors);
  }

  private StatusCode ensureCurrentWriteWorkspace(long retainedWriteBytes) {
    if (retainedWriteBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    int current = currentWriteEntries();
    return state.resourceAdmission.ensureWrite(
        retainedWriteBytes, current, current == 0);
  }

  private int currentWriteEntries() {
    return state.pendingMutations.count() + state.tupleIntents.mutationCount() + state.tupleLifecycle.count();
  }


  private static int tuplePayloadCapacity(int maximumMutations) {
    long bytes = (long) maximumMutations
        * io.riverdb.format.btree.TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES;
    return (int) Math.min(Integer.MAX_VALUE, bytes);
  }

  private StatusCode stageTupleLifecycle(
      int operation, long ownerObjectId, long keyId, long schemaId,
      long privateOwner, io.riverdb.base.tuple.TupleShape shape) {
    return stageTupleLifecycle(
        operation, ownerObjectId, keyId, schemaId, privateOwner, shape, 0);
  }

  private StatusCode stageTupleLifecycle(
      int operation, long ownerObjectId, long keyId, long schemaId,
      long privateOwner, io.riverdb.base.tuple.TupleShape shape, int cleanupEndPageId) {
    return state.tupleAccess.stageLifecycle(
        operation, ownerObjectId, keyId, schemaId,
        privateOwner, shape, cleanupEndPageId);
  }

  StatusCode acquireExclusiveKey(long space, long key) {
    return acquireExclusiveKey(space, key, true);
  }

  StatusCode tryAcquireExclusiveKey(long space, long key) {
    return acquireExclusiveKey(space, key, false);
  }

  StatusCode holdsExclusiveKey(long space, long key) {
    return state.lockWait.holdsKey(transaction, space, key, LockMode.EXCLUSIVE);
  }

  IndexedLockWait lockWait() { return state.lockWait; }

  private StatusCode acquireExclusiveKey(long space, long key, boolean wait) {
    return wait
        ? state.lockWait.acquireKey(transaction, space, key, LockMode.EXCLUSIVE)
        : state.lockWait.tryAcquireKey(transaction, space, key, LockMode.EXCLUSIVE);
  }

  StatusCode acquireSharedRangeForScan(
      long lowerSpace, long lowerKey, long upperSpace, long upperKey) {
    return state.lockWait.acquireRange(
        transaction, lowerSpace, lowerKey, upperSpace, upperKey,
        LockMode.SHARED);
  }

  StatusCode acquireTupleKey(
      long namespace, ByteBuffer key, int offset, int length, LockMode mode) {
    return state.lockWait.acquireTupleKey(
        transaction, namespace, key, offset, length, mode);
  }

  StatusCode tryAcquireTupleKey(
      long namespace, ByteBuffer key, int offset, int length, LockMode mode) {
    return state.lockWait.tryAcquireTupleKey(
        transaction, namespace, key, offset, length, mode);
  }

  StatusCode acquireTupleRangeForScan(
      long namespace,
      ByteBuffer lower, int lowerOffset, int lowerLength, boolean lowerInclusive,
      ByteBuffer upper, int upperOffset, int upperLength, boolean upperInclusive,
      LockMode mode) {
    return state.lockWait.acquireTupleRange(
        transaction, namespace,
        lower, lowerOffset, lowerLength, lowerInclusive,
        upper, upperOffset, upperLength, upperInclusive,
        mode);
  }

  StatusCode refreshForWrite() {
    if (transaction.isolationLevel() == IsolationLevel.SERIALIZABLE) {
      return refreshSerializableAfterProtection();
    }
    return transaction.isolationLevel() == IsolationLevel.READ_COMMITTED
            && !statementActive
        ? manager.refreshReadCommitted(transaction, table) : StatusCode.OK;
  }

  private boolean containsNonInsertMutation() {
    return state.pendingMutations.containsNonInsertMutation();
  }

  int findLatestPendingIndex(long space, long key) {
    return state.pendingMutations.findLatestIndex(space, key);
  }

  private void compactWriteSet() {
    state.pendingMutations.compact();
  }
}
