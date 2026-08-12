package io.riverdb.testkit.tx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.Snapshot;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.VisibilityResult;
import io.riverdb.tx.api.VisibilityState;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.version.VersionPointer;
import io.riverdb.tx.api.version.VersionReadResult;
import io.riverdb.tx.api.version.VersionRecord;
import io.riverdb.tx.spi.RecoveryTransactionView;
import org.junit.jupiter.api.Test;

/** Reusable provisional semantic suite for storage, visibility, and logical-lock providers. */
public abstract class TransactionProviderContractTest {
  private static final long DATABASE_HIGH = 11;
  private static final long DATABASE_LOW = 12;

  protected abstract TransactionProviderHarness openHarness(
      int transactionCapacity,
      int versionCapacity,
      int maxVersionBytes,
      int lockCapacity);

  @Test
  final void lifecycleAllowsOnlyDeclaredTransitionsAndTerminalDecisionsAreImmutable() {
    TransactionProviderHarness harness = openHarness(3, 1, 8, 1);
    RecoveryTransactionView view = recovery(1, TransactionState.ACTIVE, 1, 10, 0);
    assertEquals(StatusCode.OK, store(harness, view));

    view.set(
        DATABASE_HIGH, DATABASE_LOW, 1, TransactionState.COMMITTING, 1, 20, 0, 0, 0);
    assertEquals(StatusCode.OK, store(harness, view));
    view.set(
        DATABASE_HIGH, DATABASE_LOW, 1, TransactionState.COMMITTED, 1, 30, 0, 0, 7);
    assertEquals(StatusCode.OK, store(harness, view));
    assertEquals(StatusCode.OK, store(harness, view));

    view.set(
        DATABASE_HIGH, DATABASE_LOW, 1, TransactionState.ABORTED, 1, 30, 0, 0, 0);
    assertEquals(StatusCode.CONFLICT, store(harness, view));
    view.set(
        DATABASE_HIGH, DATABASE_LOW, 1, TransactionState.COMMITTED, 1, 31, 0, 0, 8);
    assertEquals(StatusCode.CONFLICT, store(harness, view));

    RecoveryTransactionView illegal = recovery(2, TransactionState.COMMITTED, 1, 1, 1);
    assertEquals(StatusCode.CONFLICT, store(harness, illegal));
  }

  @Test
  final void abortAndIndeterminateBranchesRemainDistinct() {
    TransactionProviderHarness harness = openHarness(4, 1, 8, 1);
    assertEquals(StatusCode.OK, store(harness, recovery(1, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(1, TransactionState.ABORTING, 1, 2, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(1, TransactionState.ABORTED, 1, 3, 0)));

    assertEquals(StatusCode.OK, store(harness, recovery(2, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(2, TransactionState.COMMITTING, 1, 2, 0)));
    assertEquals(
        StatusCode.OK,
        store(harness, recovery(2, TransactionState.INDETERMINATE, 1, 3, 9)));

    assertEquals(StatusCode.OK, store(harness, recovery(3, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(3, TransactionState.ABORTING, 1, 2, 0)));
    assertEquals(
        StatusCode.CONFLICT,
        store(harness, recovery(3, TransactionState.INDETERMINATE, 1, 3, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(3, TransactionState.ABORTED, 1, 3, 0)));

    assertEquals(StatusCode.OK, store(harness, recovery(4, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(4, TransactionState.COMMITTING, 1, 2, 0)));
    assertEquals(
        StatusCode.OK,
        store(harness, recovery(4, TransactionState.INDETERMINATE, 1, 3, 10)));

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(
        StatusCode.OK,
        harness.storage().lookupOutcome(
            DATABASE_HIGH, DATABASE_LOW, 1, outcome, detail()));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertTrue(outcome.isFinal());
    assertEquals(
        StatusCode.OK,
        harness.storage().lookupOutcome(
            DATABASE_HIGH, DATABASE_LOW, 2, outcome, detail()));
    assertEquals(TransactionState.INDETERMINATE, outcome.state());
    assertFalse(outcome.isFinal());

    RecoveryTransactionView committed = recovery(2, TransactionState.COMMITTED, 1, 4, 9);
    assertEquals(StatusCode.CONFLICT, store(harness, committed));
    assertEquals(
        StatusCode.OK,
        harness.recoveryStorage().resolveIndeterminate(committed, detail()));
    assertEquals(StatusCode.CONFLICT, harness.recoveryStorage().resolveIndeterminate(
        recovery(2, TransactionState.ABORTING, 1, 4, 0), detail()));

    assertEquals(StatusCode.CONFLICT, harness.recoveryStorage().resolveIndeterminate(
        recovery(4, TransactionState.ABORTED, 1, 4, 0), detail()));
    assertEquals(StatusCode.OK, harness.recoveryStorage().resolveIndeterminate(
        recovery(4, TransactionState.ABORTING, 1, 2, 0), detail()));
    TransactionOutcome loser = new TransactionOutcome();
    assertEquals(StatusCode.OK, harness.storage().lookupOutcome(
        DATABASE_HIGH, DATABASE_LOW, 4, loser, detail()));
    assertEquals(TransactionState.ABORTING, loser.state());
    assertFalse(loser.isFinal());
    RecoveryTransactionView validatedTail = new RecoveryTransactionView();
    assertEquals(StatusCode.OK, harness.recoveryStorage().lookupRecoveryTransaction(
        DATABASE_HIGH, DATABASE_LOW, 4, validatedTail, detail()));
    assertEquals(2, validatedTail.lastRecordLsn());
    assertEquals(StatusCode.OK, store(
        harness, recovery(4, TransactionState.ABORTED, 1, 5, 0)));
    assertEquals(StatusCode.CONFLICT, store(
        harness, recovery(4, TransactionState.COMMITTED, 1, 6, 10)));
  }

  @Test
  final void recoveryLookupPreservesLineageAndRejectsRegression() {
    TransactionProviderHarness harness = openHarness(2, 1, 8, 1);
    RecoveryTransactionView active = new RecoveryTransactionView().set(
        DATABASE_HIGH, DATABASE_LOW, 1, TransactionState.ACTIVE, 3, 40, 2, 30, 0);
    assertEquals(StatusCode.OK, store(harness, active));

    RecoveryTransactionView result = new RecoveryTransactionView();
    assertEquals(
        StatusCode.OK,
        harness.recoveryStorage().lookupRecoveryTransaction(
            DATABASE_HIGH, DATABASE_LOW, 1, result, detail()));
    assertEquals(3, result.lastRecordGeneration());
    assertEquals(40, result.lastRecordLsn());
    assertEquals(2, result.undoNextGeneration());
    assertEquals(30, result.undoNextLsn());

    active.set(
        DATABASE_HIGH, DATABASE_LOW, 1, TransactionState.ACTIVE, 3, 39, 2, 30, 0);
    assertEquals(StatusCode.CONFLICT, store(harness, active));
  }

  @Test
  final void transactionStatusCapacityReturnsBackpressureAndMissingOutcomeIsExplicit() {
    TransactionProviderHarness harness = openHarness(1, 1, 8, 1);
    assertEquals(StatusCode.OK, store(harness, recovery(1, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        store(harness, recovery(2, TransactionState.ACTIVE, 1, 1, 0)));

    TransactionOutcome result = new TransactionOutcome();
    assertEquals(
        StatusCode.RETRY,
        harness.storage().lookupOutcome(
            DATABASE_HIGH, DATABASE_LOW, 9, result, detail()));
    assertFalse(result.isAvailable());
    assertEquals(
        StatusCode.CONFLICT,
        harness.storage().lookupOutcome(99, 100, 1, result, detail()));
  }

  @Test
  final void snapshotVisibilityUsesPublicationBoundaryActiveSetAndStatus() {
    TransactionProviderHarness harness = openHarness(7, 1, 8, 1);
    commit(harness, 1, 5);
    commit(harness, 2, 8);
    abort(harness, 3);
    assertEquals(StatusCode.OK, store(harness, recovery(4, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(5, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(
        harness, recovery(5, TransactionState.COMMITTING, 1, 2, 0)));
    assertEquals(StatusCode.OK, store(
        harness, recovery(5, TransactionState.INDETERMINATE, 1, 3, 9)));
    commit(harness, 6, 6);

    TransactionContext reader = context(9, snapshot(7, new long[] {1}));
    VisibilityResult result = new VisibilityResult();
    assertVisibility(harness, reader, 9, StatusCode.OK, VisibilityState.OWN_WRITE, result);
    assertVisibility(harness, reader, 1, StatusCode.OK, VisibilityState.HIDDEN, result);
    assertVisibility(harness, reader, 2, StatusCode.OK, VisibilityState.HIDDEN, result);
    assertVisibility(harness, reader, 3, StatusCode.OK, VisibilityState.HIDDEN, result);
    assertVisibility(harness, reader, 4, StatusCode.OK, VisibilityState.HIDDEN, result);
    assertVisibility(
        harness, reader, 5, StatusCode.FENCED, VisibilityState.INDETERMINATE, result);
    assertVisibility(
        harness, reader, 99, StatusCode.RETRY, VisibilityState.OUTCOME_UNAVAILABLE, result);
    assertVisibility(harness, reader, 6, StatusCode.OK, VisibilityState.VISIBLE, result);
  }

  @Test
  final void versionStorageCopiesInputAndOutputAcrossExplicitOwnershipBoundaries() {
    TransactionProviderHarness harness = openHarness(2, 2, 8, 1);
    assertEquals(StatusCode.OK, store(harness, recovery(1, TransactionState.ACTIVE, 1, 1, 0)));
    TransactionContext context = context(1, snapshot(0, new long[] {1}));
    byte[] input = {1, 2, 3};
    VersionRecord append = new VersionRecord().set(
        1, 0, 0, 0, 0, input, 0, input.length);
    VersionPointer pointer = new VersionPointer();
    assertEquals(
        StatusCode.OK,
        harness.storage().appendVersion(context, append, pointer, detail()));
    input[0] = 9;

    byte[] destination = new byte[3];
    VersionReadResult read = new VersionReadResult().useDestination(
        destination, 0, destination.length);
    assertEquals(StatusCode.OK, harness.storage().readVersion(pointer, read, detail()));
    assertArrayEquals(new byte[] {1, 2, 3}, destination);
    assertEquals(DATABASE_HIGH, pointer.databaseIncarnationHigh());
    assertEquals(DATABASE_LOW, pointer.databaseIncarnationLow());

    VersionPointer foreign = new VersionPointer().set(
        99, 100, pointer.storeGeneration(), pointer.address());
    assertEquals(
        StatusCode.CONFLICT,
        harness.storage().readVersion(foreign, read, detail()));

    VersionReadResult tooSmall = new VersionReadResult().useDestination(new byte[2], 0, 2);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        harness.storage().readVersion(pointer, tooSmall, detail()));
    assertEquals(3, tooSmall.requiredPayloadBytes());
  }

  @Test
  final void versionCapacityAndPayloadCapacityAreBounded() {
    TransactionProviderHarness harness = openHarness(1, 1, 2, 1);
    assertEquals(StatusCode.OK, store(harness, recovery(1, TransactionState.ACTIVE, 1, 1, 0)));
    TransactionContext context = context(1, snapshot(0, new long[] {1}));
    VersionPointer pointer = new VersionPointer();
    VersionRecord oversized = new VersionRecord().set(
        1, 0, 0, 0, 0, new byte[3], 0, 3);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        harness.storage().appendVersion(context, oversized, pointer, detail()));
    VersionRecord accepted = new VersionRecord().set(
        1, 0, 0, 0, 0, new byte[2], 0, 2);
    assertEquals(
        StatusCode.OK,
        harness.storage().appendVersion(context, accepted, pointer, detail()));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        harness.storage().appendVersion(context, accepted, new VersionPointer(), detail()));
  }

  @Test
  final void lockTokensEnforceContentionOwnershipReleaseAndReuse() {
    TransactionProviderHarness harness = openHarness(2, 1, 8, 2);
    assertEquals(StatusCode.OK, store(harness, recovery(1, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(2, TransactionState.ACTIVE, 1, 1, 0)));
    TransactionContext first = context(1, snapshot(0, new long[] {1, 2}));
    TransactionContext second = context(2, snapshot(0, new long[] {1, 2}));
    LockRequest request = lockRequest(LockScope.KEY, LockMode.EXCLUSIVE, 0);
    LockToken token = new LockToken();
    assertEquals(
        StatusCode.OK, harness.locks().tryAcquire(first, request, 1, token, detail()));
    assertTrue(token.isActive());
    assertEquals(StatusCode.CONFLICT, token.reset());
    assertEquals(
        StatusCode.CONFLICT,
        harness.locks().tryAcquire(first, request, 1, token, detail()));
    assertEquals(
        StatusCode.RETRY,
        harness.locks().tryAcquire(second, request, 1, new LockToken(), detail()));
    assertEquals(StatusCode.OK, harness.locks().release(token, detail()));
    assertFalse(token.isActive());
    assertEquals(StatusCode.CONFLICT, harness.locks().release(token, detail()));
    LockToken replacement = new LockToken();
    assertEquals(
        StatusCode.OK, harness.locks().tryAcquire(second, request, 1, replacement, detail()));
    assertEquals(2, replacement.transactionId());
    assertEquals(StatusCode.CONFLICT, harness.locks().release(token, detail()));
    assertEquals(
        StatusCode.RETRY,
        harness.locks().tryAcquire(first, request, 1, new LockToken(), detail()));

    LockToken forged = new LockToken();
    assertEquals(
        StatusCode.OK,
        forged.claim(
            123,
            456,
            replacement.providerGeneration(),
            replacement.capabilityToken(),
            replacement.transactionId(),
            replacement.slot()));
    assertEquals(StatusCode.CONFLICT, harness.locks().release(forged, detail()));
    assertEquals(
        StatusCode.RETRY,
        harness.locks().tryAcquire(first, request, 1, new LockToken(), detail()));
    assertEquals(StatusCode.OK, harness.locks().release(replacement, detail()));
  }

  @Test
  final void lockCapabilityCannotBeReleasedByAnotherProvider() {
    TransactionProviderHarness firstHarness = openHarness(1, 1, 8, 1);
    TransactionProviderHarness secondHarness = openHarness(1, 1, 8, 1);
    assertEquals(
        StatusCode.OK,
        store(firstHarness, recovery(1, TransactionState.ACTIVE, 1, 1, 0)));
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK, firstHarness.locks().tryAcquire(
        context(1, snapshot(0, new long[] {1})),
        lockRequest(LockScope.KEY, LockMode.EXCLUSIVE, 0),
        1,
        token,
        detail()));
    assertEquals(StatusCode.CONFLICT, secondHarness.locks().release(token, detail()));
    assertTrue(token.isActive());
    assertEquals(StatusCode.OK, firstHarness.locks().release(token, detail()));
  }

  @Test
  final void lockNamespacesDeadlinesCancellationAndCapacityAreExplicit() {
    TransactionProviderHarness harness = openHarness(2, 1, 8, 1);
    assertEquals(StatusCode.OK, store(harness, recovery(1, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(harness, recovery(2, TransactionState.ACTIVE, 1, 1, 0)));
    TransactionContext first = context(1, snapshot(0, new long[] {1, 2}));
    TransactionContext second = context(2, snapshot(0, new long[] {1, 2}));
    LockToken held = new LockToken();
    assertEquals(StatusCode.OK, harness.locks().tryAcquire(
        first, lockRequest(LockScope.ROW, LockMode.SHARED, 0), 1, held, detail()));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, harness.locks().tryAcquire(
        second, lockRequest(LockScope.KEY, LockMode.EXCLUSIVE, 0), 1,
        new LockToken(), detail()));
    assertEquals(StatusCode.TIMEOUT, harness.locks().tryAcquire(
        second, lockRequest(LockScope.ROW, LockMode.EXCLUSIVE, 5), 5,
        new LockToken(), detail()));

    MutableCancellationToken cancellation = new MutableCancellationToken();
    cancellation.cancel();
    TransactionContext cancelled = new TransactionContext(
        DATABASE_HIGH, DATABASE_LOW, 2, IsolationLevel.READ_COMMITTED,
        snapshot(0, new long[] {1, 2}), cancellation);
    assertEquals(StatusCode.CANCELLED, harness.locks().tryAcquire(
        cancelled, lockRequest(LockScope.ROW, LockMode.SHARED, 0), 1,
        new LockToken(), detail()));
  }

  private static void assertVisibility(
      TransactionProviderHarness harness,
      TransactionContext context,
      long owner,
      StatusCode expectedStatus,
      VisibilityState expectedState,
      VisibilityResult result) {
    assertEquals(
        expectedStatus,
        harness.visibility().resolve(context, owner, result, detail()));
    assertEquals(expectedState, result.state());
  }

  private static void commit(
      TransactionProviderHarness harness, long transactionId, long commitSequence) {
    assertEquals(StatusCode.OK, store(
        harness, recovery(transactionId, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(
        harness, recovery(transactionId, TransactionState.COMMITTING, 1, 2, 0)));
    assertEquals(StatusCode.OK, store(
        harness, recovery(transactionId, TransactionState.COMMITTED, 1, 3, commitSequence)));
  }

  private static void abort(TransactionProviderHarness harness, long transactionId) {
    assertEquals(StatusCode.OK, store(
        harness, recovery(transactionId, TransactionState.ACTIVE, 1, 1, 0)));
    assertEquals(StatusCode.OK, store(
        harness, recovery(transactionId, TransactionState.ABORTING, 1, 2, 0)));
    assertEquals(StatusCode.OK, store(
        harness, recovery(transactionId, TransactionState.ABORTED, 1, 3, 0)));
  }

  private static StatusCode store(
      TransactionProviderHarness harness, RecoveryTransactionView view) {
    return harness.storage().storeRecoveryView(view, detail());
  }

  private static RecoveryTransactionView recovery(
      long transactionId,
      TransactionState state,
      long lastGeneration,
      long lastLsn,
      long commitSequence) {
    return new RecoveryTransactionView().set(
        DATABASE_HIGH,
        DATABASE_LOW,
        transactionId,
        state,
        lastGeneration,
        lastLsn,
        0,
        0,
        commitSequence);
  }

  private static TransactionContext context(long transactionId, Snapshot snapshot) {
    return new TransactionContext(
        DATABASE_HIGH,
        DATABASE_LOW,
        transactionId,
        IsolationLevel.REPEATABLE_READ,
        snapshot,
        CancellationToken.NONE);
  }

  private static Snapshot snapshot(long visibleCommitSequence, long[] activeTransactions) {
    return new DeterministicSnapshot(
        DATABASE_HIGH,
        DATABASE_LOW,
        1,
        visibleCommitSequence,
        activeTransactions,
        activeTransactions.length);
  }

  private static LockRequest lockRequest(
      LockScope scope, LockMode mode, long deadlineNanos) {
    return new LockRequest().set(scope, 7, 8, mode, deadlineNanos);
  }

  private static StatusDetail detail() {
    return new StatusDetail(64);
  }
}
