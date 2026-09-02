package io.riverdb.engine.table;

import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;

/** Owns the bounded mutable workspaces retained for one reusable transaction session. */
final class IndexedSessionState {
  final IndexedTransactionResourceAdmission resourceAdmission;
  final IndexedSessionCursors cursors = new IndexedSessionCursors();
  final PendingMutationBuffer pendingMutations;
  final IndexedTupleIntentJournal tupleIntents;
  final IndexedLogicalRowIdFloors logicalRowFloors;
  final IndexedTupleIndexLifecycleBatch tupleLifecycle;
  final IndexedLockWait lockWait;
  final IndexedSessionSavepoints savepoints;
  final IndexedGroupCommitCoordinator groupCommit;
  final IndexedVacuum automaticVacuum;
  final int automaticVacuumCapacityReserve;
  final IndexedCommitResult commitResult = new IndexedCommitResult();
  final IndexedGroupCommitRequest groupCommitRequest;
  final IndexedRelationalWalPlan groupWalPlan = new IndexedRelationalWalPlan();
  final TransactionOutcome maintenanceOutcome = new TransactionOutcome();
  final IndexedMutationTarget mutationTarget = new IndexedMutationTarget();
  final IndexedCurrentRowAccess currentRows;
  final IndexedTransactionScanCoordinator scans;
  final IndexedTransactionTupleScans tupleScans;
  final IndexedTransactionWriteSet writeSet;
  final IndexedTransactionReadAccess readAccess;
  final IndexedSessionTupleAccess tupleAccess;

  IndexedSessionState(
      IndexedTransactionSession owner,
      TransactionManager manager,
      Transaction transaction,
      int maximumRowBytes,
      int maximumMutations,
      int tuplePayloadCapacity,
      DatabaseResourceGovernor governor,
      IndexedGroupCommitCoordinator groupCommitCoordinator,
      IndexedVacuum versionVacuum,
      int vacuumCapacityReserve) {
    resourceAdmission = new IndexedTransactionResourceAdmission(governor);
    groupCommitRequest = new IndexedGroupCommitRequest(owner);
    pendingMutations = new PendingMutationBuffer(maximumMutations, maximumRowBytes);
    tupleIntents = new IndexedTupleIntentJournal(
        maximumMutations, maximumMutations, tuplePayloadCapacity);
    logicalRowFloors = new IndexedLogicalRowIdFloors(maximumMutations);
    tupleLifecycle = new IndexedTupleIndexLifecycleBatch(maximumMutations);
    lockWait = new IndexedLockWait(manager);
    savepoints = new IndexedSessionSavepoints(
        owner, transaction, pendingMutations, tupleIntents, tupleLifecycle);
    groupCommit = groupCommitCoordinator;
    automaticVacuum = versionVacuum;
    automaticVacuumCapacityReserve = vacuumCapacityReserve;
    scans = new IndexedTransactionScanCoordinator(owner);
    tupleScans = new IndexedTransactionTupleScans(owner);
    writeSet = new IndexedTransactionWriteSet(owner);
    readAccess = new IndexedTransactionReadAccess(owner);
    currentRows = new IndexedCurrentRowAccess(owner);
    tupleAccess = new IndexedSessionTupleAccess(owner);
  }

  boolean hasHybridWork() {
    return pendingMutations.count() > 0 || tupleIntents.mutationCount() > 0
        || tupleLifecycle.active() || logicalRowFloors.count() > 0;
  }
}
