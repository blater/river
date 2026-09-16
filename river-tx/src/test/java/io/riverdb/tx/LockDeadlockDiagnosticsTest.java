package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import org.junit.jupiter.api.Test;

final class LockDeadlockDiagnosticsTest {
  private static final LockDeadlockDiagnosticsConfig DIAGNOSTICS =
      diagnostics(1L << 20, 2, 4, 8, 1, 8);

  @Test
  void configurationUsesOnlyCallerBudgetAndAddressabilityBoundaries() {
    LockDeadlockDiagnosticsConfig.Result result = new LockDeadlockDiagnosticsConfig.Result();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        LockDeadlockDiagnosticsConfig.createBounded(269, 1, 1, 1, 0, 2, result));
    assertEquals(null, result.config());
    assertEquals(StatusCode.OK,
        LockDeadlockDiagnosticsConfig.createBounded(270, 1, 1, 1, 0, 2, result));
    assertEquals(270, result.config().retainedPayloadBytes());
    assertEquals(270, result.config().maximumRetainedBytes());
    assertEquals(0, result.config().exemplarsPerSignature());

    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        LockDeadlockDiagnosticsConfig.createBounded(
            Long.MAX_VALUE, Integer.MAX_VALUE, 2, 1, 0, 2, result));
    assertEquals(null, result.config());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        LockDeadlockDiagnosticsConfig.createBounded(1_024, 1, 1, 1, -1, 2, result));
  }

  @Test
  void publicSnapshotAdmissionChecksBudgetWhileInternalCopyUsesDimensions() {
    LockDeadlockDiagnosticsConfig first = diagnostics(4_096, 1, 2, 2, 0, 8);
    LockDeadlockDiagnosticsConfig second = diagnostics(8_192, 1, 2, 2, 0, 8);
    LockManager manager = new LockManager(new LockMemoryEnvelope(16L << 20), first);
    LockDeadlockDiagnosticsSnapshot admitted = manager.newDeadlockDiagnosticsSnapshot();
    LockDeadlockDiagnosticsSnapshot differentBudget = new LockDeadlockDiagnosticsSnapshot(second);
    assertEquals(StatusCode.OK, manager.snapshotDeadlockDiagnostics(admitted));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        manager.snapshotDeadlockDiagnostics(differentBudget));
    admitted.counters.totalVictimSelections = 7;
    differentBudget.copyFrom(admitted);
    assertEquals(7, differentBudget.counters().totalVictimSelections());
  }

  @Test
  void disabledStorageStillAccountsEveryVictimCleanupAndOutcome() {
    LockDeadlockDiagnosticsConfig disabled = LockDeadlockDiagnosticsConfig.disabled();
    Fixture fixture = new Fixture(disabled);
    runTwoOwnerCycle(fixture, 1, 2, 10, 20, 101, 202, 9);
    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.DEADLOCK);
    fixture.table.lifecycle.releaseAll(1, 1, StatusCode.CANCELLED);

    LockDeadlockDiagnosticsSnapshot snapshot = new LockDeadlockDiagnosticsSnapshot(disabled);
    fixture.table.snapshotDeadlocks(snapshot);
    assertFalse(snapshot.config.enabled());
    assertEquals(0, snapshot.config.retainedPayloadBytes());
    assertEquals(1, snapshot.counters.totalVictimSelections());
    assertEquals(1, snapshot.counters.victimTransactionOutcomes());
    assertEquals(1, snapshot.counters.queuedRequestsCancelled());
    assertEquals(1, snapshot.counters.holdingsReleased());
    assertEquals(0, snapshot.counters.signatureCount());
    assertEquals(0, snapshot.counters.victimEventCount());
    assertEquals(0, snapshot.counters.exemplarCount());
    assertFalse(snapshot.validForDiagnosticGate());
  }

  @Test
  void zeroExemplarShapeKeepsCompleteAggregateAndSignatureAccounting() {
    LockDeadlockDiagnosticsConfig config = diagnostics(4_096, 1, 2, 2, 0, 8);
    Fixture fixture = new Fixture(config);
    runTwoOwnerCycle(fixture, 1, 2, 10, 20, 101, 202, 9);
    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.DEADLOCK);
    fixture.table.lifecycle.releaseAll(1, 1, StatusCode.CANCELLED);
    runTwoOwnerCycle(fixture, 3, 4, 30, 40, 303, 404, 9);
    fixture.table.lifecycle.releaseAll(4, 1, StatusCode.DEADLOCK);
    fixture.table.lifecycle.releaseAll(3, 1, StatusCode.CANCELLED);

    LockDeadlockDiagnosticsSnapshot snapshot = new LockDeadlockDiagnosticsSnapshot(config);
    fixture.table.snapshotDeadlocks(snapshot);
    assertEquals(2, snapshot.counters.totalVictimSelections());
    assertEquals(2, snapshot.counters.victimTransactionOutcomes());
    assertEquals(2, snapshot.signatures.victimSelectionsAt(0));
    assertEquals(2, snapshot.signatures.victimOutcomesAt(0));
    assertEquals(2, snapshot.counters.victimEventCount());
    assertEquals(0, snapshot.counters.exemplarCount());
    assertEquals(0, snapshot.counters.exemplarOverflows());
    assertTrue(snapshot.validForDiagnosticGate());
  }

  @Test
  void transactionTagEpochVictimOutcomeAndReuseReconcileEndToEnd() {
    TransactionManager manager = new TransactionManager(
        11, 13, 1, 2, new LockMemoryEnvelope(16L << 20),
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS, DIAGNOSTICS);
    Transaction first = new Transaction(2);
    Transaction second = new Transaction(2);
    assertEquals(StatusCode.OK, first.configureDiagnostics(101, 0, 7));
    assertEquals(StatusCode.OK, second.configureDiagnostics(202, 0, 7));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, first));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, second));
    assertEquals(StatusCode.OK, first.updateDiagnosticStep(11));
    assertEquals(StatusCode.OK, second.updateDiagnosticStep(17));

    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(64);
    LockRequest left = key(71, 10, LockMode.EXCLUSIVE);
    LockRequest right = key(71, 20, LockMode.EXCLUSIVE);
    LockToken firstHeld = new LockToken();
    LockToken secondHeld = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        first.context(), first.transactionGeneration(), left, 0, firstHeld, detail));
    assertEquals(StatusCode.OK, locks.tryAcquire(
        second.context(), second.transactionGeneration(), right, 0, secondHeld, detail));
    Wait firstWait = new Wait();
    Wait secondWait = new Wait();
    assertEquals(StatusCode.RETRY, locks.enqueue(
        first.context(), first.transactionGeneration(), 1, 1, right, 0,
        firstWait.lane, firstWait.handle, detail));
    assertEquals(StatusCode.DEADLOCK, locks.enqueue(
        second.context(), second.transactionGeneration(), 1, 1, left, 0,
        secondWait.lane, secondWait.handle, detail));
    assertEquals(StatusCode.OK, manager.abort(second, new TransactionOutcome()));

    LockDeadlockDiagnosticsSnapshot snapshot = manager.newDeadlockDiagnosticsSnapshot();
    assertEquals(StatusCode.OK, manager.snapshotDeadlockDiagnostics(snapshot));
    assertEquals(1, snapshot.counters.totalVictimSelections());
    assertEquals(1, snapshot.counters.victimTransactionOutcomes());
    assertEquals(1, snapshot.counters.queuedRequestsCancelled());
    assertEquals(1, snapshot.counters.holdingsReleased());
    assertEquals(1, snapshot.counters.victimEventCount());
    assertEquals(7, snapshot.events.epochAt(0));
    assertEquals(202, snapshot.events.diagnosticTagAt(0));
    assertEquals(17, snapshot.events.diagnosticStepTagAt(0));
    assertEquals(StatusCode.DEADLOCK, snapshot.events.outcomeStatusAt(0));
    assertTrue(snapshot.events.outcomeSequenceAt(0) > snapshot.events.sequenceAt(0));
    assertTrue(snapshot.events.cleanupValidAt(0));
    assertTrue(snapshot.validForDiagnosticGate());

    LockToken granted = new LockToken();
    assertEquals(StatusCode.OK, locks.consume(
        first.context(), first.transactionGeneration(), firstWait.lane,
        firstWait.handle, granted, detail));
    assertEquals(StatusCode.OK, locks.release(
        first.context(), first.transactionGeneration(), granted, detail));
    assertEquals(StatusCode.OK, locks.release(
        first.context(), first.transactionGeneration(), firstHeld, detail));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(secondHeld, detail));
    assertEquals(StatusCode.OK, manager.abort(first, new TransactionOutcome()));
    assertEquals(StatusCode.CONFLICT, first.updateDiagnosticStep(19));

    assertEquals(StatusCode.OK, second.reset());
    assertEquals(StatusCode.OK, second.configureDiagnostics(303, 0, 8));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, second));
    assertEquals(StatusCode.OK, manager.abort(second, new TransactionOutcome()));
  }

  @Test
  void stableFingerprintAggregatesEverySelectionAndBoundsExemplars() {
    Fixture fixture = new Fixture(DIAGNOSTICS);
    runTwoOwnerCycle(fixture, 1, 2, 10, 20, 101, 202, 9);
    fixture.table.lifecycle.releaseAll(2, 1, StatusCode.DEADLOCK);
    fixture.table.lifecycle.releaseAll(1, 1, StatusCode.CANCELLED);
    runTwoOwnerCycle(fixture, 3, 4, 30, 40, 303, 404, 9);
    fixture.table.lifecycle.releaseAll(4, 1, StatusCode.DEADLOCK);
    fixture.table.lifecycle.releaseAll(3, 1, StatusCode.CANCELLED);
    runTwoOwnerCycle(fixture, 5, 6, 50, 60, 505, 606, 10);
    fixture.table.lifecycle.releaseAll(6, 1, StatusCode.DEADLOCK);
    fixture.table.lifecycle.releaseAll(5, 1, StatusCode.CANCELLED);

    LockDeadlockDiagnosticsSnapshot snapshot = new LockDeadlockDiagnosticsSnapshot(DIAGNOSTICS);
    fixture.table.snapshotDeadlocks(snapshot);
    assertEquals(3, snapshot.counters.totalVictimSelections());
    assertEquals(3, snapshot.counters.victimTransactionOutcomes());
    assertEquals(2, snapshot.counters.signatureCount());
    assertEquals(2, snapshot.signatures.victimSelectionsAt(0));
    assertEquals(2, snapshot.signatures.victimOutcomesAt(0));
    assertEquals(1, snapshot.signatures.victimSelectionsAt(1));
    assertEquals(snapshot.signatures.fingerprintAt(0), snapshot.signatures.fingerprintAt(1));
    assertEquals(3, snapshot.counters.victimEventCount());
    assertEquals(10, snapshot.events.epochAt(2));
    assertEquals(2, snapshot.counters.exemplarCount());
    assertEquals(1, snapshot.counters.exemplarOverflows());
    assertNotEquals(0, snapshot.signatures.fingerprintAt(0));
    assertTrue(snapshot.events.sequenceAt(1) > snapshot.events.sequenceAt(0));
    assertTrue(snapshot.validForDiagnosticGate());
  }

  @Test
  void compatibleReaderFairnessCycleRecordsTheEnforcedFifoPredicate() {
    Fixture fixture = new Fixture(DIAGNOSTICS);
    acquire(fixture, 1, 10, LockMode.SHARED);
    acquire(fixture, 2, 20, LockMode.EXCLUSIVE);
    acquire(fixture, 3, 30, LockMode.EXCLUSIVE);
    tag(fixture, 1, 501, 4);
    tag(fixture, 2, 502, 4);
    tag(fixture, 3, 503, 4);
    enqueue(fixture, 2, 1, 10, LockMode.EXCLUSIVE, StatusCode.RETRY);
    enqueue(fixture, 2, 2, 30, LockMode.EXCLUSIVE, StatusCode.RETRY);
    enqueue(fixture, 3, 1, 10, LockMode.SHARED, StatusCode.DEADLOCK);

    LockDeadlockDiagnosticsSnapshot snapshot = new LockDeadlockDiagnosticsSnapshot(DIAGNOSTICS);
    fixture.table.snapshotDeadlocks(snapshot);
    assertEquals(1, snapshot.counters.totalVictimSelections());
    assertEquals(1, snapshot.counters.exemplarCount());
    boolean fairness = false;
    int count = snapshot.exemplars.edgeCountAt(0);
    for (int index = 0; index < count; index++) {
      int edge = snapshot.exemplars.edgeIndex(0, index);
      if (snapshot.edges.kindAt(edge) == LockDeadlockEdgeKind.FIFO_FAIRNESS) {
        fairness = true;
        assertEquals(LockQueueKind.ORDINARY, snapshot.edges.waiterQueueKindAt(edge));
        assertEquals(LockQueueKind.ORDINARY, snapshot.edges.blockerQueueKindAt(edge));
        assertEquals(LockMode.SHARED, snapshot.edges.requestedModeAt(edge));
        assertEquals(LockMode.EXCLUSIVE, snapshot.edges.blockerRequestedModeAt(edge));
        assertEquals(null, snapshot.edges.heldModeAt(edge));
        assertEquals(LockGrantPrecondition.NO_EARLIER_INCOMPATIBLE_WAITER,
            snapshot.edges.preconditionAt(edge));
        assertFalse(snapshot.edges.grantPredicateResultAt(edge));
      }
    }
    assertTrue(fairness);
    assertEquals(0, snapshot.counters.selfValidationFailures());
  }

  @Test
  void selfValidationRejectsAnEdgeTheSchedulerDoesNotEnforce() {
    Fixture fixture = new Fixture(DIAGNOSTICS);
    acquire(fixture, 1, 10, LockMode.EXCLUSIVE);
    Wait wait = enqueue(fixture, 2, 1, 10, LockMode.EXCLUSIVE, StatusCode.RETRY);
    long resource = fixture.table.state.directory.resource(key(1, 10, LockMode.EXCLUSIVE));
    long holding = fixture.table.state.directory.holding(resource, 1, 1);
    long waiter = fixture.table.state.directory.transaction(2, 1);
    assertTrue(fixture.table.deadlocks.selfValidEdge(
        waiter, wait.lane.requestSlot(), holding, resource,
        LockDeadlockEdgeKind.ACTIVE_OWNER,
        LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER));
    assertFalse(fixture.table.deadlocks.selfValidEdge(
        waiter, wait.lane.requestSlot(), holding, resource,
        LockDeadlockEdgeKind.FIFO_FAIRNESS,
        LockGrantPrecondition.FIFO_QUEUE_HEAD));
  }

  @Test
  void signatureTableDetectsCollisionCapacityAndEpochOverflow() {
    LockDeadlockDiagnosticsConfig config =
        diagnostics(4_096, 1, 1, 1, 1, 2);
    Fixture fixture = new Fixture(config);
    assertEquals(0, fixture.table.deadlocks.admitSignature(1, 17, 23));
    assertEquals(-1, fixture.table.deadlocks.admitSignature(1, 17, 29));
    assertEquals(-1, fixture.table.deadlocks.admitSignature(1, 31, 37));
    assertEquals(-1, fixture.table.deadlocks.admitSignature(2, 41, 43));

    LockDeadlockDiagnosticsSnapshot snapshot = new LockDeadlockDiagnosticsSnapshot(config);
    fixture.table.snapshotDeadlocks(snapshot);
    assertEquals(1, snapshot.counters.signatureCount());
    assertEquals(1, snapshot.counters.fingerprintCollisions());
    assertEquals(3, snapshot.counters.fingerprintOverflows());
    assertEquals(1, snapshot.counters.epochOverflows());
    assertFalse(snapshot.validForDiagnosticGate());
  }

  @Test
  void eventAndCycleEdgeOverflowAreExplicitAndDoNotHideVictimSelection() {
    LockDeadlockDiagnosticsConfig eventConfig =
        diagnostics(8_192, 1, 2, 1, 1, 8);
    Fixture events = new Fixture(eventConfig);
    runTwoOwnerCycle(events, 1, 2, 10, 20, 1, 2, 3);
    events.table.lifecycle.releaseAll(2, 1, StatusCode.DEADLOCK);
    events.table.lifecycle.releaseAll(1, 1, StatusCode.CANCELLED);
    runTwoOwnerCycle(events, 3, 4, 30, 40, 3, 4, 3);
    events.table.lifecycle.releaseAll(4, 1, StatusCode.DEADLOCK);
    events.table.lifecycle.releaseAll(3, 1, StatusCode.CANCELLED);
    LockDeadlockDiagnosticsSnapshot eventSnapshot =
        new LockDeadlockDiagnosticsSnapshot(eventConfig);
    events.table.snapshotDeadlocks(eventSnapshot);
    assertEquals(2, eventSnapshot.counters().totalVictimSelections());
    assertEquals(2, eventSnapshot.counters().victimTransactionOutcomes());
    assertEquals(2, events.table.deadlockVictimSelections());
    assertEquals(1, eventSnapshot.counters().victimEventCount());
    assertEquals(1, eventSnapshot.counters().victimEventOverflows());
    assertFalse(eventSnapshot.validForDiagnosticGate());

    LockDeadlockDiagnosticsConfig edgeConfig =
        diagnostics(8_192, 1, 2, 2, 1, 2);
    Fixture edges = new Fixture(edgeConfig);
    acquire(edges, 1, 10, LockMode.EXCLUSIVE);
    acquire(edges, 2, 20, LockMode.EXCLUSIVE);
    acquire(edges, 3, 30, LockMode.EXCLUSIVE);
    enqueue(edges, 1, 1, 20, LockMode.EXCLUSIVE, StatusCode.RETRY);
    enqueue(edges, 2, 1, 30, LockMode.EXCLUSIVE, StatusCode.RETRY);
    enqueue(edges, 3, 1, 10, LockMode.EXCLUSIVE, StatusCode.DEADLOCK);
    LockDeadlockDiagnosticsSnapshot edgeSnapshot =
        new LockDeadlockDiagnosticsSnapshot(edgeConfig);
    edges.table.snapshotDeadlocks(edgeSnapshot);
    assertEquals(1, edgeSnapshot.counters().totalVictimSelections());
    assertEquals(1, edgeSnapshot.counters().cycleEdgeOverflows());
    assertEquals(1, edgeSnapshot.counters().victimEventCount());
    assertFalse(edgeSnapshot.validForDiagnosticGate());
  }

  private static void runTwoOwnerCycle(
      Fixture fixture, long first, long second, long left, long right,
      long firstTag, long secondTag, long epoch) {
    acquire(fixture, first, left, LockMode.EXCLUSIVE);
    acquire(fixture, second, right, LockMode.EXCLUSIVE);
    tag(fixture, first, firstTag, epoch);
    tag(fixture, second, secondTag, epoch);
    enqueue(fixture, first, 1, right, LockMode.EXCLUSIVE, StatusCode.RETRY);
    enqueue(fixture, second, 1, left, LockMode.EXCLUSIVE, StatusCode.DEADLOCK);
  }

  private static LockToken acquire(
      Fixture fixture, long transaction, long identity, LockMode mode) {
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK, fixture.table.tryAcquire(
        transaction, 1, transaction, key(1, identity, mode), token));
    return token;
  }

  private static Wait enqueue(
      Fixture fixture, long transaction, long lane, long identity,
      LockMode mode, StatusCode expected) {
    Wait wait = new Wait();
    assertEquals(expected, fixture.table.enqueue(
        transaction, 1, transaction, lane, 1,
        key(1, identity, mode), wait.lane, wait.handle));
    return wait;
  }

  private static void tag(Fixture fixture, long id, long tag, long epoch) {
    long transaction = fixture.table.state.directory.transaction(id, 1);
    LockExactTransactionStore.Chunk transactions =
        fixture.table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    transactions.diagnosticTags[offset] = tag;
    transactions.metricsEpochs[offset] = epoch;
  }

  private static LockRequest key(long space, long key, LockMode mode) {
    return new LockRequest().setKey(space, key, mode, 0);
  }

  private static LockDeadlockDiagnosticsConfig diagnostics(
      long bytes, int epochs, int signatures, int events, int exemplars, int edges) {
    LockDeadlockDiagnosticsConfig.Result result = new LockDeadlockDiagnosticsConfig.Result();
    StatusCode status = LockDeadlockDiagnosticsConfig.createBounded(
        bytes, epochs, signatures, events, exemplars, edges, result);
    if (!status.isOk()) throw new AssertionError("diagnostic fixture admission failed: " + status);
    return result.config();
  }

  private static final class Wait {
    final LockExecutionLane lane = new LockExecutionLane();
    final LockWaitHandle handle = new LockWaitHandle();
  }

  private static final class Fixture {
    final LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(32L << 20));
    final LockExactTable table;

    Fixture(LockDeadlockDiagnosticsConfig config) {
      table = new LockExactTable(new Object(), 83, arena, config);
    }
  }
}
