package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.api.TransactionState;

/** Session-owned logical commit carrier frozen before coordinator admission. */
final class IndexedPreparedLogicalCommit {
  private final Transaction transaction;
  private final IndexedTransactionResourceAdmission resources;
  private final PendingMutationBuffer pending;
  private final IndexedTupleIntentJournal intents;
  private final IndexedTupleIndexLifecycleBatch lifecycle;
  private final IndexedLogicalRowIdFloors floors;
  private final IndexedRelationalWalPlan walPlan;
  private final IndexedHybridLogicalSizing logicalSizing =
      new IndexedHybridLogicalSizing();
  private long walBytes;
  private int writeEntries;
  private int pendingCount;
  private int intentCount;
  private int lifecycleCount;
  private int floorCount;
  private long pendingGeneration;
  private long intentGeneration;
  private long lifecycleGeneration;
  private long floorGeneration;
  private boolean active;

  IndexedPreparedLogicalCommit(
      Transaction owner,
      IndexedTransactionResourceAdmission resourceAdmission,
      PendingMutationBuffer pendingMutations,
      IndexedTupleIntentJournal tupleIntents,
      IndexedTupleIndexLifecycleBatch tupleLifecycle,
      IndexedLogicalRowIdFloors logicalRowFloors,
      IndexedRelationalWalPlan plan) {
    transaction = owner;
    resources = resourceAdmission;
    pending = pendingMutations;
    intents = tupleIntents;
    lifecycle = tupleLifecycle;
    floors = logicalRowFloors;
    walPlan = plan;
  }

  StatusCode prepare() {
    if (active || transaction.state() != TransactionState.ACTIVE) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = prepareHybridSizing();
    if (!status.isOk()) return fail(status);

    status = resources.ensureCommit(
        writeEntries, 0, logicalSizing.versions(), walBytes);
    if (!status.isOk()) return fail(status);
    long retained = walPlan.accountedBytes();
    long required = walPlan.accountedBytesForChunkCapacity(chunkCount());
    if (required < retained) return fail(StatusCode.INVARIANT_BROKEN);
    status = resources.ensureWalRetainedBytes(required);
    if (status.isOk()) status = intents.reserveCompilation(logicalSizing);
    if (status.isOk()) status = walPlan.reserveChunkCapacity(chunkCount());
    if (!status.isOk()) return fail(status);

    pendingCount = pending.count();
    intentCount = intents.mutationCount();
    lifecycleCount = lifecycle.count();
    floorCount = floors.count();
    pendingGeneration = pending.generation();
    intentGeneration = intents.generation();
    lifecycleGeneration = lifecycle.generation();
    floorGeneration = floors.generation();
    if (pendingGeneration <= 0 || intentGeneration <= 0
        || lifecycleGeneration <= 0 || floorGeneration <= 0) {
      return fail(StatusCode.RESOURCE_EXHAUSTED);
    }
    active = true;
    return StatusCode.OK;
  }

  private StatusCode prepareHybridSizing() {
    StatusCode status = logicalSizing.measure(pending, intents, lifecycle, floors);
    if (!status.isOk()) return status;
    writeEntries = currentWriteEntries();
    if (writeEntries < 0) return StatusCode.RESOURCE_EXHAUSTED;
    walBytes = logicalSizing.walBytes();
    return StatusCode.OK;
  }

  StatusCode admitStagedPages(int exactChangedPages) {
    if (!valid() || exactChangedPages < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    return resources.ensureCommit(
        writeEntries, exactChangedPages, logicalSizing.versions(), walBytes);
  }

  boolean valid() {
    TransactionState state = transaction.state();
    return active
        && (state == TransactionState.PREPARED || state == TransactionState.COMMITTING)
        && pending.count() == pendingCount
        && intents.mutationCount() == intentCount
        && lifecycle.count() == lifecycleCount
        && floors.count() == floorCount
        && pending.generation() == pendingGeneration
        && intents.generation() == intentGeneration
        && lifecycle.generation() == lifecycleGeneration
        && floors.generation() == floorGeneration;
  }

  void reset() {
    active = false;
    walBytes = 0;
    writeEntries = pendingCount = intentCount = lifecycleCount = floorCount = 0;
    pendingGeneration = intentGeneration = lifecycleGeneration = floorGeneration = 0;
    logicalSizing.reset();
    walPlan.reset();
  }

  private StatusCode fail(StatusCode status) {
    reset();
    return status;
  }

  private int chunkCount() {
    return logicalSizing.chunks();
  }

  private int currentWriteEntries() {
    long entries = (long) pending.count() + intents.mutationCount() + lifecycle.count();
    return entries > Integer.MAX_VALUE ? -1 : (int) entries;
  }

  Transaction transaction() { return transaction; }
  PendingMutationBuffer pendingMutations() { return pending; }
  IndexedTupleIntentJournal tupleIntents() { return intents; }
  IndexedTupleIndexLifecycleBatch tupleLifecycle() { return lifecycle; }
  IndexedLogicalRowIdFloors logicalRowFloors() { return floors; }
  IndexedRelationalWalPlan walPlan() { return walPlan; }
  long admittedWalBytes() { return walBytes; }
  int admittedWalRecords() { return logicalSizing.chunks(); }
  int admittedWriteEntries() { return writeEntries; }
  int admittedVersionOperations() { return logicalSizing.versions(); }
}
