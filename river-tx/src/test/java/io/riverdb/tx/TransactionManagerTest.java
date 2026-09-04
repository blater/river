package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TransactionManagerTest {
  @Test
  void sourceAdmissionIsCheckedBeforeClaimingATransactionIdentity() {
    TransactionManager manager = new TransactionManager(3, 5, 2, 1, lockMemory());
    Transaction transaction = new Transaction(1);
    AdmissionSource source = new AdmissionSource();
    source.status = StatusCode.RETRY;
    source.sequence = 11;

    assertEquals(
        StatusCode.RETRY,
        manager.begin(IsolationLevel.REPEATABLE_READ, source, transaction));
    assertFalse(transaction.isActiveHandle());

    source.status = StatusCode.OK;
    assertEquals(
        StatusCode.OK,
        manager.begin(IsolationLevel.REPEATABLE_READ, source, transaction));
    assertEquals(2, transaction.transactionId());
    assertEquals(11, transaction.snapshot().visibleCommitSequence());
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
  }

  @Test
  void invalidBeginDoesNotConsultAdmissionSourceAndInvalidSourceStateIsInvariantFailure() {
    TransactionManager manager = new TransactionManager(3, 5, 2, 1, lockMemory());
    Transaction transaction = new Transaction(1);
    AdmissionSource source = new AdmissionSource();
    source.status = StatusCode.RETRY;
    source.sequence = 11;

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        manager.begin(null, source, transaction));
    assertEquals(0, source.admissionCalls);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        manager.begin(IsolationLevel.REPEATABLE_READ, source, null));
    assertEquals(0, source.admissionCalls);

    source.status = null;
    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        manager.begin(IsolationLevel.REPEATABLE_READ, source, transaction));
    assertEquals(1, source.admissionCalls);
    assertFalse(transaction.isActiveHandle());

    source.status = StatusCode.OK;
    source.sequence = -1;
    assertEquals(
        StatusCode.INVARIANT_BROKEN,
        manager.begin(IsolationLevel.REPEATABLE_READ, source, transaction));
    assertEquals(2, source.admissionCalls);
    assertFalse(transaction.isActiveHandle());
  }

  @Test
  void admitsDiagnosticIdentityWithoutDetailedDeadlockEvidence() {
    TransactionManager manager = new TransactionManager(5, 7, 2, 1, lockMemory());
    Transaction transaction = new Transaction(1);
    assertEquals(StatusCode.OK, transaction.configureDiagnostics(101, 11, 13));
    assertEquals(
        StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 17, transaction));

    assertEquals(StatusCode.OK, transaction.updateDiagnosticStep(19));
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
    assertEquals(0, manager.activeTransactionCount());
  }

  @Test
  void oldestVisibleCommitSequenceTracksBeginRefreshAndCompletion() {
    TransactionManager manager = new TransactionManager(7, 11, 2, 3, lockMemory());
    Transaction first = new Transaction(3);
    Transaction second = new Transaction(3);
    assertEquals(Long.MAX_VALUE, manager.oldestVisibleCommitSequence());
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.READ_COMMITTED, 20, first));
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.REPEATABLE_READ, 30, second));
    assertEquals(20, manager.oldestVisibleCommitSequence());
    assertEquals(StatusCode.OK, manager.refreshReadCommitted(first, 40));
    assertEquals(30, manager.oldestVisibleCommitSequence());
    assertEquals(StatusCode.OK, manager.abort(second, new TransactionOutcome()));
    assertEquals(40, manager.oldestVisibleCommitSequence());
    assertEquals(StatusCode.OK, manager.abort(first, new TransactionOutcome()));
    assertEquals(Long.MAX_VALUE, manager.oldestVisibleCommitSequence());
  }

  @Test
  void beginPublishesACompleteSnapshotBeforeActivatingContext() throws Exception {
    TransactionManager manager = new TransactionManager(13, 17, 2, 1, lockMemory());
    Transaction transaction = new Transaction(1);
    TransactionContext context = transaction.context();
    LockManager lockManager = (LockManager) manager.lockService();
    StatusCode[] status = new StatusCode[1];
    Thread begin = new Thread(() -> status[0] = manager.begin(
        IsolationLevel.REPEATABLE_READ, 41, transaction));
    assertFalse(context.isActive());

    synchronized (lockManager) {
      begin.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (begin.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(Thread.State.BLOCKED, begin.getState());
      assertFalse(context.isActive());
    }
    begin.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(begin.isAlive());
    assertEquals(StatusCode.OK, status[0]);
    assertTrue(context.isActive());
    assertEquals(13, context.databaseIncarnationHigh());
    assertEquals(17, context.databaseIncarnationLow());
    assertEquals(41, context.snapshot().visibleCommitSequence());
    assertSame(transaction.snapshot(), context.snapshot());
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
  }

  @Test
  void quiescentBoundaryExcludesTransactionAdmission() throws Exception {
    TransactionManager manager = new TransactionManager(17, 19, 2, 1, lockMemory());
    BlockingQuiescentParticipant participant = new BlockingQuiescentParticipant();
    StatusCode[] boundaryStatus = new StatusCode[1];
    StatusCode[] beginStatus = new StatusCode[1];
    Transaction transaction = new Transaction(1);
    Thread boundary = new Thread(() ->
        boundaryStatus[0] = manager.atQuiescentBoundary(participant));
    Thread begin = new Thread(() ->
        beginStatus[0] = manager.begin(IsolationLevel.SERIALIZABLE, 1, transaction));

    boundary.start();
    assertTrue(participant.entered.await(5, TimeUnit.SECONDS));
    begin.start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (begin.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(Thread.State.BLOCKED, begin.getState());

    participant.release.countDown();
    boundary.join(TimeUnit.SECONDS.toMillis(5));
    begin.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(boundary.isAlive());
    assertFalse(begin.isAlive());
    assertEquals(StatusCode.OK, boundaryStatus[0]);
    assertEquals(StatusCode.OK, beginStatus[0]);
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
  }

  @Test
  void commitFreezeRejectsAdmissionWhileDurableDecisionIsInProgress() throws Exception {
    TransactionManager manager = new TransactionManager(19, 23, 2, 2, lockMemory());
    Transaction transaction = new Transaction(1);
    Transaction contender = new Transaction(2);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, transaction));
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, contender));
    TransactionContext context = transaction.context();
    long generation = transaction.transactionGeneration();
    LockService locks = manager.lockService();
    LockRequest request = new LockRequest().setExact(
        LockScope.ROW, 31, 32, LockMode.EXCLUSIVE, 0);
    LockToken held = new LockToken();
    StatusDetail detail = new StatusDetail(32);
    assertEquals(StatusCode.OK,
        locks.tryAcquire(context, generation, request, 0, held, detail));
    BlockingParticipant participant = new BlockingParticipant(2);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> committed = executor.submit(() -> manager.commit(
          transaction, participant, new TransactionOutcome()));
      assertTrue(participant.entered.await(5, TimeUnit.SECONDS));
      assertEquals(TransactionState.COMMITTING, transaction.state());
      assertFalse(context.isActive());
      assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, locks.tryAcquire(
          context, generation, request, 0, new LockToken(), new StatusDetail(32)));
      assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
          locks.release(context, generation, held, detail));
      assertEquals(StatusCode.CONFLICT, locks.acknowledge(held, detail));
      assertEquals(StatusCode.RETRY, locks.tryAcquire(
          contender.context(), contender.transactionGeneration(),
          request, 0, new LockToken(), detail));
      participant.release.countDown();
      assertEquals(StatusCode.OK, committed.get(5, TimeUnit.SECONDS));
      assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(held, detail));
      assertFalse(held.isActive());
      LockToken acquired = new LockToken();
      assertEquals(StatusCode.OK, locks.tryAcquire(
          contender.context(), contender.transactionGeneration(), request, 0, acquired, detail));
      assertEquals(StatusCode.OK, manager.abort(contender, new TransactionOutcome()));
      assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(acquired, detail));
    } finally {
      participant.release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void prepareFreezesTransactionButRetainsSnapshotAndLocksUntilCompletion() {
    TransactionManager manager = new TransactionManager(21, 25, 2, 2, lockMemory());
    Transaction transaction = new Transaction(2);
    Transaction peer = new Transaction(2);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 17, transaction));
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 19, peer));
    LockToken held = new LockToken();
    assertEquals(StatusCode.OK, manager.tryAcquireKey(transaction, 33, 34, held));

    long snapshotSequence = transaction.snapshot().snapshotSequence();
    long visibleCommitSequence = transaction.snapshot().visibleCommitSequence();
    int snapshotActiveCount = transaction.snapshot().activeTransactionCount();
    assertEquals(0, snapshotActiveCount);
    assertEquals(1, manager.activeLockCount());

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, manager.prepareCommit(transaction, outcome));
    assertEquals(TransactionState.PREPARED, transaction.state());
    assertFalse(transaction.context().isActive());
    assertEquals(2, manager.activeTransactionCount());
    assertEquals(1, manager.activeLockCount());
    assertSame(transaction.snapshot(), transaction.context().snapshot());
    assertEquals(snapshotSequence, transaction.snapshot().snapshotSequence());
    assertEquals(visibleCommitSequence, transaction.snapshot().visibleCommitSequence());
    assertEquals(snapshotActiveCount, transaction.snapshot().activeTransactionCount());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        manager.tryAcquireKey(transaction, 33, 35, new LockToken()));
    assertEquals(StatusCode.RETRY,
        manager.tryAcquireKey(peer, 33, 34, new LockToken()));

    assertEquals(StatusCode.OK, manager.abortPreparedCommitGroup(
        new Transaction[] {transaction}, new TransactionOutcome[] {new TransactionOutcome()},
        1, StatusCode.CANCELLED));
    assertEquals(StatusCode.OK, manager.abort(peer, new TransactionOutcome()));
  }

  @Test
  void commitGroupRejectsActiveOrUnpreparedMembers() {
    TransactionManager manager = new TransactionManager(26, 30, 2, 2, lockMemory());
    Transaction prepared = new Transaction(2);
    Transaction active = new Transaction(2);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, prepared));
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, active));
    assertEquals(StatusCode.OK, manager.prepareCommit(prepared, new TransactionOutcome()));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        manager.beginCommitGroup(new Transaction[] {active}, 1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        manager.beginCommitGroup(new Transaction[] {prepared, active}, 2));
    assertEquals(TransactionState.PREPARED, prepared.state());
    assertEquals(TransactionState.ACTIVE, active.state());
    assertTrue(prepared.isOwnedBy(manager));

    assertEquals(StatusCode.OK, manager.abortPreparedCommitGroup(
        new Transaction[] {prepared}, new TransactionOutcome[] {new TransactionOutcome()},
        1, StatusCode.CANCELLED));
    assertEquals(StatusCode.OK, manager.abort(active, new TransactionOutcome()));
  }

  @Test
  void abortPreparedCleansLocksAndSnapshotExactlyOnce() {
    TransactionManager manager = new TransactionManager(31, 35, 2, 1, lockMemory());
    Transaction transaction = new Transaction(1);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 7, transaction));
    LockToken held = new LockToken();
    assertEquals(StatusCode.OK, manager.tryAcquireKey(transaction, 37, 38, held));
    assertEquals(StatusCode.OK, manager.prepareCommit(transaction, new TransactionOutcome()));
    assertEquals(1, manager.activeTransactionCount());
    assertEquals(1, manager.activeLockCount());

    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode failure = StatusCode.RESOURCE_EXHAUSTED;
    assertEquals(StatusCode.OK, manager.abortPreparedCommitGroup(
        new Transaction[] {transaction}, new TransactionOutcome[] {outcome}, 1, failure));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    assertEquals(0, manager.waitingLockCount());
    assertEquals(Long.MAX_VALUE, manager.oldestVisibleCommitSequence());
    assertFalse(transaction.context().isActive());
    assertEquals(StatusCode.NOT_OWNER, manager.release(transaction, held));

    TransactionOutcome secondOutcome = new TransactionOutcome();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, manager.abortPreparedCommitGroup(
        new Transaction[] {transaction}, new TransactionOutcome[] {secondOutcome}, 1, failure));
    assertFalse(secondOutcome.isAvailable());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());

    Transaction reusable = new Transaction(1);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 7, reusable));
    assertEquals(StatusCode.OK, manager.abort(reusable, new TransactionOutcome()));
  }

  @Test
  void abortPreparedCommitGroupPropagatesPreDecisionFailureToEveryOutcome() {
    TransactionManager manager = new TransactionManager(39, 43, 2, 2, lockMemory());
    Transaction first = new Transaction(2);
    Transaction second = new Transaction(2);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 11, first));
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 11, second));
    LockToken firstLock = new LockToken();
    LockToken secondLock = new LockToken();
    assertEquals(StatusCode.OK, manager.tryAcquireKey(first, 45, 46, firstLock));
    assertEquals(StatusCode.OK, manager.tryAcquireKey(second, 45, 47, secondLock));
    assertEquals(StatusCode.OK, manager.prepareCommit(first, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.prepareCommit(second, new TransactionOutcome()));
    assertEquals(2, manager.activeTransactionCount());
    assertEquals(2, manager.activeLockCount());

    TransactionOutcome firstOutcome = new TransactionOutcome();
    TransactionOutcome secondOutcome = new TransactionOutcome();
    StatusCode failure = StatusCode.RESOURCE_EXHAUSTED;
    assertEquals(StatusCode.OK, manager.abortPreparedCommitGroup(
        new Transaction[] {first, second},
        new TransactionOutcome[] {firstOutcome, secondOutcome}, 2, failure));
    assertEquals(TransactionState.ABORTED, first.state());
    assertEquals(TransactionState.ABORTED, second.state());
    assertEquals(TransactionState.ABORTED, firstOutcome.state());
    assertEquals(TransactionState.ABORTED, secondOutcome.state());
    assertEquals(first.transactionId(), firstOutcome.transactionId());
    assertEquals(second.transactionId(), secondOutcome.transactionId());
    assertEquals(0, firstOutcome.commitSequence());
    assertEquals(0, secondOutcome.commitSequence());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    assertEquals(StatusCode.NOT_OWNER, manager.release(first, firstLock));
    assertEquals(StatusCode.NOT_OWNER, manager.release(second, secondLock));
  }

  @Test
  void queuedExactRequestRejectsCommitBeforeParticipantRuns() {
    TransactionManager manager = new TransactionManager(29, 31, 2, 2, lockMemory());
    Transaction owner = new Transaction(2);
    Transaction waiter = new Transaction(2);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, waiter));
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(32);
    LockRequest request = new LockRequest().setExact(
        LockScope.ROW, 41, 42, LockMode.EXCLUSIVE, 0);
    LockToken ownerToken = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        owner.context(), owner.transactionGeneration(), request, 0, ownerToken, detail));
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, locks.enqueue(
        waiter.context(), waiter.transactionGeneration(),
        1, 1, request, 0, lane, handle, detail));

    FakeParticipant participant = new FakeParticipant();
    participant.set(StatusCode.OK, 2);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.CONFLICT, manager.commit(waiter, participant, outcome));
    assertEquals(0, participant.calls);
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(LockWaitState.FAILED, handle.state());
    assertEquals(StatusCode.CONFLICT, locks.await(lane, handle, detail));
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(ownerToken, detail));
    assertEquals(StatusCode.OK, ownerToken.reset());
  }

  @Test
  void canonicalTokenCannotWeakenCommitGroupProtectionAndIsInvalidatedAtTerminal() {
    TransactionManager manager = new TransactionManager(24, 26, 2, 2, lockMemory());
    Transaction owner = new Transaction(2);
    Transaction contender = new Transaction(2);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, contender));
    LockToken held = new LockToken();
    assertEquals(StatusCode.OK, manager.tryAcquireSharedKey(owner, 4, 5, held));
    assertEquals(StatusCode.OK, manager.prepareCommit(owner, new TransactionOutcome()));
    assertEquals(StatusCode.OK,
        manager.beginCommitGroup(new Transaction[] {owner}, 1));
    assertEquals(TransactionState.COMMITTING, owner.state());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, manager.release(owner, held));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, manager.upgradeKey(owner, held));
    assertEquals(StatusCode.RETRY,
        manager.tryAcquireKey(contender, 4, 5, new LockToken()));
    TransactionOutcome ownerOutcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, manager.failCommitGroup(
        new Transaction[] {owner}, new TransactionOutcome[] {ownerOutcome},
        1, StatusCode.CONFLICT));
    assertEquals(StatusCode.NOT_OWNER, manager.release(owner, held));
    LockToken acquired = new LockToken();
    assertEquals(StatusCode.OK, manager.tryAcquireKey(contender, 4, 5, acquired));
    assertEquals(StatusCode.OK, manager.abort(contender, new TransactionOutcome()));
    assertEquals(StatusCode.NOT_OWNER, manager.release(contender, acquired));
  }

  @Test
  void canonicalTokenIsInvalidatedByAbortDecision() {
    TransactionManager manager = new TransactionManager(27, 28, 2, 1, lockMemory());
    Transaction transaction = new Transaction(1);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, transaction));
    LockToken token = new LockToken();
    assertEquals(StatusCode.OK, manager.tryAcquireKey(transaction, 6, 7, token));
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
    assertEquals(StatusCode.NOT_OWNER, manager.release(transaction, token));
    assertEquals(StatusCode.OK, token.reset());
  }

  @Test
  void grantedUnconsumedExactRequestRejectsReadOnlyCommit() {
    TransactionManager manager = new TransactionManager(37, 41, 2, 2, lockMemory());
    Transaction owner = new Transaction(2);
    Transaction waiter = new Transaction(2);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, waiter));
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(32);
    LockRequest request = new LockRequest().setExact(
        LockScope.SCHEMA, 43, 44, LockMode.EXCLUSIVE, 0);
    LockToken ownerToken = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        owner.context(), owner.transactionGeneration(), request, 0, ownerToken, detail));
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, locks.enqueue(
        waiter.context(), waiter.transactionGeneration(),
        2, 1, request, 0, lane, handle, detail));
    assertEquals(StatusCode.OK, locks.release(
        owner.context(), owner.transactionGeneration(), ownerToken, detail));
    assertEquals(LockWaitState.GRANTED, handle.state());

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.CONFLICT, manager.commitReadOnly(waiter, outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(LockWaitState.FAILED, handle.state());
    assertEquals(StatusCode.CONFLICT, locks.await(lane, handle, detail));
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
  }

  @Test
  void pendingExactRequestRejectsPreparationWithoutFreezingPeer() {
    TransactionManager manager = new TransactionManager(43, 47, 2, 3, lockMemory());
    Transaction owner = new Transaction(3);
    Transaction blocked = new Transaction(3);
    Transaction peer = new Transaction(3);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, blocked));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, peer));
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(32);
    LockRequest request = new LockRequest().setExact(
        LockScope.ROW, 51, 52, LockMode.EXCLUSIVE, 0);
    LockToken ownerToken = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        owner.context(), owner.transactionGeneration(), request, 0, ownerToken, detail));
    assertEquals(StatusCode.RETRY, locks.enqueue(
        blocked.context(), blocked.transactionGeneration(), 1, 1, request, 0,
        new LockExecutionLane(), new LockWaitHandle(), detail));

    TransactionOutcome blockedOutcome = new TransactionOutcome();
    assertEquals(StatusCode.CONFLICT, manager.prepareCommit(blocked, blockedOutcome));
    assertEquals(TransactionState.ABORTED, blocked.state());
    assertEquals(TransactionState.ABORTED, blockedOutcome.state());
    assertEquals(TransactionState.ACTIVE, peer.state());
    assertFalse(blocked.context().isActive());
    assertTrue(peer.context().isActive());
    assertEquals(StatusCode.OK, manager.abort(peer, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
  }

  @Test
  void frozenExactWaitPublishesTerminalOutcomeInsteadOfCancelOrTimeout() throws Exception {
    TransactionManager manager = new TransactionManager(45, 49, 2, 2, lockMemory());
    Transaction owner = new Transaction(2);
    Transaction waiter = new Transaction(2);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, waiter));
    LockManager locks = (LockManager) manager.lockService();
    StatusDetail detail = new StatusDetail(32);
    LockRequest request = new LockRequest().setExact(
        LockScope.ROW, 55, 56, LockMode.EXCLUSIVE, 1);
    LockToken ownerToken = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        owner.context(), owner.transactionGeneration(), request, 0, ownerToken, detail));
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, locks.enqueue(
        waiter.context(), waiter.transactionGeneration(),
        4, 1, request, 0, lane, handle, detail));
    assertEquals(StatusCode.CONFLICT, locks.lifecycle.freezeForCommit(waiter));
    assertEquals(StatusCode.RETRY, locks.cancel(lane, handle, detail));
    assertEquals(LockWaitState.QUEUED, handle.state());

    Thread waitingThread = Thread.currentThread();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      waitingThread.interrupt();
      Future<?> terminal = executor.submit(() -> {
        while (waitingThread.isInterrupted()) Thread.onSpinWait();
        locks.lifecycle.complete(
            waiter.transactionId(), waiter.transactionGeneration(), StatusCode.CONFLICT);
      });
      try {
        assertEquals(StatusCode.CONFLICT,
            locks.await(lane, handle, new StatusDetail(32)));
        assertTrue(waitingThread.isInterrupted());
      } finally {
        Thread.interrupted();
      }
      terminal.get(1, TimeUnit.SECONDS);
      assertEquals(LockWaitState.FAILED, handle.state());
      assertFalse(lane.isPending());
      assertEquals(StatusCode.OK, lane.reset());
      assertEquals(StatusCode.OK, handle.reset());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(ownerToken, detail));
  }

  @Test
  void commitGroupRetainsExactHoldingsUntilPublishOrFail() {
    TransactionManager manager = new TransactionManager(53, 59, 2, 2, lockMemory());
    Transaction member = new Transaction(2);
    Transaction contender = new Transaction(2);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, member));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, contender));
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(32);
    LockRequest request = new LockRequest().setExact(
        LockScope.ROW, 61, 62, LockMode.EXCLUSIVE, 0);
    LockToken memberToken = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        member.context(), member.transactionGeneration(), request, 0, memberToken, detail));
    assertEquals(StatusCode.OK, manager.prepareCommit(member, new TransactionOutcome()));
    assertEquals(StatusCode.OK,
        manager.beginCommitGroup(new Transaction[] {member}, 1));
    assertFalse(member.context().isActive());
    assertEquals(1, manager.activeLockCount());
    assertEquals(StatusCode.RETRY, locks.tryAcquire(
        contender.context(), contender.transactionGeneration(),
        request, 0, new LockToken(), detail));

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, manager.failCommitGroup(
        new Transaction[] {member}, new TransactionOutcome[] {outcome},
        1, StatusCode.CONFLICT));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(0, manager.activeLockCount());
    LockToken acquired = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        contender.context(), contender.transactionGeneration(), request, 0, acquired, detail));
    assertEquals(StatusCode.OK, manager.abort(contender, new TransactionOutcome()));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(acquired, detail));
  }

  @Test
  void commitGroupValidationRejectsAliasedOutcomesWithoutMutatingThem() {
    TransactionManager manager = new TransactionManager(57, 61, 2, 2, lockMemory());
    Transaction first = new Transaction(2);
    Transaction second = new Transaction(2);
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, first));
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, second));
    assertEquals(StatusCode.OK, manager.prepareCommit(first, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.prepareCommit(second, new TransactionOutcome()));
    Transaction[] transactions = {first, second};
    assertEquals(StatusCode.OK, manager.beginCommitGroup(transactions, 2));

    TransactionOutcome aliased = new TransactionOutcome().set(
        701, 703, 709, TransactionState.COMMITTED, 711);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, manager.publishCommitGroup(
        transactions, new TransactionOutcome[] {aliased, aliased},
        new long[] {2, 3}, 2, () -> StatusCode.OK,
        new TransactionGroupCompletionTimings()));
    assertEquals(701, aliased.databaseIncarnationHigh());
    assertEquals(709, aliased.transactionId());
    assertEquals(711, aliased.commitSequence());
    assertEquals(TransactionState.COMMITTING, first.state());
    assertEquals(TransactionState.COMMITTING, second.state());

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, manager.failCommitGroup(
        transactions, new TransactionOutcome[] {aliased, aliased},
        2, StatusCode.CONFLICT));
    assertEquals(701, aliased.databaseIncarnationHigh());
    assertEquals(709, aliased.transactionId());
    assertEquals(711, aliased.commitSequence());

    TransactionOutcome firstOutcome = new TransactionOutcome();
    TransactionOutcome secondOutcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, manager.failCommitGroup(
        transactions, new TransactionOutcome[] {firstOutcome, secondOutcome},
        2, StatusCode.CONFLICT));
    assertEquals(TransactionState.ABORTED, firstOutcome.state());
    assertEquals(TransactionState.ABORTED, secondOutcome.state());
    assertEquals(0, manager.activeTransactionCount());
  }

  @Test
  void publicContextAcquiresAndCommitReleasesExactHoldingAcrossReuse() {
    TransactionManager manager = new TransactionManager(3, 5, 2, 1, lockMemory());
    Transaction transaction = new Transaction(1);
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(64);
    LockRequest request = new LockRequest().setExact(
        LockScope.ROW, 91, 92, LockMode.EXCLUSIVE, 0);
    LockToken token = new LockToken();

    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, transaction));
    TransactionContext context = transaction.context();
    long firstGeneration = context.transactionGeneration();
    assertSame(context, transaction.context());
    assertTrue(context.isActive());
    assertEquals(StatusCode.OK,
        locks.tryAcquire(context, firstGeneration, request, 0, token, detail));
    assertEquals(1, manager.activeLockCount());

    FakeParticipant participant = new FakeParticipant();
    participant.set(StatusCode.OK, 2);
    assertEquals(StatusCode.OK,
        manager.commit(transaction, participant, new TransactionOutcome()));
    assertFalse(context.isActive());
    assertEquals(0, manager.activeLockCount());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        locks.tryAcquire(context, firstGeneration, request, 0, new LockToken(), detail));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(token, detail));

    assertEquals(StatusCode.OK, transaction.reset());
    assertEquals(firstGeneration, transaction.transactionGeneration());
    assertEquals(firstGeneration, context.transactionGeneration());
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 2, transaction));
    assertSame(context, transaction.context());
    assertEquals(firstGeneration + 1, context.transactionGeneration());
    assertTrue(context.isActive());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        locks.tryAcquire(context, firstGeneration, request, 0, new LockToken(), detail));
    assertEquals(StatusCode.OK, locks.tryAcquire(
        context, context.transactionGeneration(), request, 0, new LockToken(), detail));
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
    assertFalse(context.isActive());
  }

  @Test
  void publicDeadlockVictimUsesBeginOrderRatherThanFirstLockAdmission() {
    TransactionManager manager = new TransactionManager(67, 71, 2, 2, lockMemory());
    Transaction older = new Transaction(2);
    Transaction younger = new Transaction(2);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, older));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, younger));
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(64);
    LockRequest first = new LockRequest().setExact(
        LockScope.ROW, 111, 1, LockMode.EXCLUSIVE, 0);
    LockRequest second = new LockRequest().setExact(
        LockScope.ROW, 111, 2, LockMode.EXCLUSIVE, 0);
    LockToken youngerSecond = new LockToken();
    LockToken olderFirst = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        younger.context(), younger.transactionGeneration(),
        second, 0, youngerSecond, detail));
    assertEquals(StatusCode.OK, locks.tryAcquire(
        older.context(), older.transactionGeneration(), first, 0, olderFirst, detail));

    LockExecutionLane olderLane = new LockExecutionLane();
    LockWaitHandle olderWait = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, locks.enqueue(
        older.context(), older.transactionGeneration(), 1, 1,
        second, 0, olderLane, olderWait, detail));
    LockExecutionLane youngerLane = new LockExecutionLane();
    LockWaitHandle youngerWait = new LockWaitHandle();
    assertEquals(StatusCode.DEADLOCK, locks.enqueue(
        younger.context(), younger.transactionGeneration(), 1, 1,
        first, 0, youngerLane, youngerWait, detail));
    assertEquals(LockWaitState.DEADLOCK, youngerWait.state());
    assertEquals(LockWaitState.GRANTED, olderWait.state());
    assertEquals(StatusCode.DEADLOCK, locks.await(youngerLane, youngerWait, detail));
    assertEquals(StatusCode.OK, manager.abort(younger, new TransactionOutcome()));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(youngerSecond, detail));

    LockToken olderSecond = new LockToken();
    assertEquals(StatusCode.OK, locks.await(olderLane, olderWait, detail));
    assertEquals(StatusCode.OK, locks.consume(
        older.context(), older.transactionGeneration(),
        olderLane, olderWait, olderSecond, detail));
    assertEquals(StatusCode.OK, locks.release(
        older.context(), older.transactionGeneration(), olderSecond, detail));
    assertEquals(StatusCode.OK, locks.release(
        older.context(), older.transactionGeneration(), olderFirst, detail));
    assertEquals(StatusCode.OK, manager.abort(older, new TransactionOutcome()));

    assertEquals(StatusCode.OK, younger.reset());
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, younger));
    assertEquals(StatusCode.OK, youngerLane.reset());
    assertEquals(StatusCode.OK, youngerWait.reset());
    LockToken reused = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        younger.context(), younger.transactionGeneration(), first, 0, reused, detail));
    assertEquals(StatusCode.OK, manager.abort(younger, new TransactionOutcome()));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(reused, detail));
  }

  @Test
  void armedMultiLaneVictimWakesEveryLaneAndTerminalAbortRecyclesTombstone()
      throws Exception {
    TransactionManager manager = new TransactionManager(73, 79, 2, 3, lockMemory());
    Transaction survivor = new Transaction(3);
    Transaction victim = new Transaction(3);
    Transaction third = new Transaction(3);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, survivor));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, victim));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, third));
    LockManager locks = (LockManager) manager.lockService();
    StatusDetail detail = new StatusDetail(64);
    LockRequest first = new LockRequest().setExact(
        LockScope.ROW, 121, 1, LockMode.EXCLUSIVE, 0);
    LockRequest second = new LockRequest().setExact(
        LockScope.ROW, 121, 2, LockMode.EXCLUSIVE, 0);
    LockRequest thirdResource = new LockRequest().setExact(
        LockScope.ROW, 121, 3, LockMode.EXCLUSIVE, 0);
    LockToken survivorFirst = new LockToken();
    LockToken victimSecond = new LockToken();
    LockToken thirdToken = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        survivor.context(), survivor.transactionGeneration(),
        first, 0, survivorFirst, detail));
    assertEquals(StatusCode.OK, locks.tryAcquire(
        victim.context(), victim.transactionGeneration(),
        second, 0, victimSecond, detail));
    assertEquals(StatusCode.OK, locks.tryAcquire(
        third.context(), third.transactionGeneration(),
        thirdResource, 0, thirdToken, detail));

    LockExecutionLane victimFirstLane = new LockExecutionLane();
    LockWaitHandle victimFirstWait = new LockWaitHandle();
    LockExecutionLane victimSecondLane = new LockExecutionLane();
    LockWaitHandle victimSecondWait = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, locks.enqueue(
        victim.context(), victim.transactionGeneration(), 1, 1,
        first, 0, victimFirstLane, victimFirstWait, detail));
    assertEquals(StatusCode.RETRY, locks.enqueue(
        victim.context(), victim.transactionGeneration(), 2, 1,
        thirdResource, 0, victimSecondLane, victimSecondWait, detail));

    StatusCode[] outcomes = new StatusCode[2];
    Thread firstWaiter = new Thread(() -> outcomes[0] = locks.await(
        victimFirstLane, victimFirstWait, new StatusDetail(32)));
    Thread secondWaiter = new Thread(() -> outcomes[1] = locks.await(
        victimSecondLane, victimSecondWait, new StatusDetail(32)));
    firstWaiter.start();
    secondWaiter.start();
    long parkDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while ((!parked(firstWaiter) || !parked(secondWaiter))
        && System.nanoTime() < parkDeadline) Thread.onSpinWait();
    assertTrue(parked(firstWaiter));
    assertTrue(parked(secondWaiter));

    LockExecutionLane survivorLane = new LockExecutionLane();
    LockWaitHandle survivorWait = new LockWaitHandle();
    assertEquals(StatusCode.OK, locks.enqueue(
        survivor.context(), survivor.transactionGeneration(), 1, 1,
        second, 0, survivorLane, survivorWait, detail));
    firstWaiter.join(TimeUnit.SECONDS.toMillis(5));
    secondWaiter.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(firstWaiter.isAlive());
    assertFalse(secondWaiter.isAlive());
    assertEquals(StatusCode.DEADLOCK, outcomes[0]);
    assertEquals(StatusCode.DEADLOCK, outcomes[1]);
    assertEquals(LockWaitState.DEADLOCK, victimFirstWait.state());
    assertEquals(LockWaitState.DEADLOCK, victimSecondWait.state());
    assertEquals(LockWaitState.GRANTED, survivorWait.state());

    long victimId = victim.transactionId();
    long victimGeneration = victim.transactionGeneration();
    assertEquals(StatusCode.OK, manager.abort(victim, new TransactionOutcome()));
    assertEquals(-1, locks.exact.state.directory.transaction(victimId, victimGeneration));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(victimSecond, detail));

    LockToken survivorSecond = new LockToken();
    assertEquals(StatusCode.OK, locks.await(survivorLane, survivorWait, detail));
    assertEquals(StatusCode.OK, locks.consume(
        survivor.context(), survivor.transactionGeneration(),
        survivorLane, survivorWait, survivorSecond, detail));
    assertEquals(StatusCode.OK, locks.release(
        survivor.context(), survivor.transactionGeneration(), survivorSecond, detail));
    assertEquals(StatusCode.OK, locks.release(
        survivor.context(), survivor.transactionGeneration(), survivorFirst, detail));
    assertEquals(StatusCode.OK, locks.release(
        third.context(), third.transactionGeneration(), thirdToken, detail));
    assertEquals(StatusCode.OK, manager.abort(survivor, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(third, new TransactionOutcome()));

    assertEquals(StatusCode.OK, victim.reset());
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, victim));
    LockToken reused = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        victim.context(), victim.transactionGeneration(), first, 0, reused, detail));
    assertEquals(StatusCode.OK, manager.abort(victim, new TransactionOutcome()));
    assertEquals(StatusCode.NOT_OWNER, locks.acknowledge(reused, detail));
  }

  @Test
  void abortCancelsExactQueuedAndGrantedUnconsumedRequests() {
    TransactionManager manager = new TransactionManager(4, 6, 2, 3, lockMemory());
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(64);
    LockRequest request = new LockRequest().setExact(
        LockScope.SCHEMA, 101, 102, LockMode.EXCLUSIVE, 0);
    Transaction owner = new Transaction(3);
    Transaction queued = new Transaction(3);
    Transaction granted = new Transaction(3);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, queued));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, granted));
    LockToken ownerToken = new LockToken();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        owner.context(), owner.transactionGeneration(), request, 0, ownerToken, detail));

    LockExecutionLane queuedLane = new LockExecutionLane();
    LockWaitHandle queuedHandle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, locks.enqueue(
        queued.context(), queued.transactionGeneration(),
        1, 1, request, 0, queuedLane, queuedHandle, detail));
    assertEquals(StatusCode.OK, manager.abort(queued, new TransactionOutcome()));
    assertEquals(LockWaitState.CANCELLED, queuedHandle.state());
    assertEquals(StatusCode.CANCELLED, locks.await(queuedLane, queuedHandle, detail));

    LockExecutionLane grantedLane = new LockExecutionLane();
    LockWaitHandle grantedHandle = new LockWaitHandle();
    assertEquals(StatusCode.RETRY, locks.enqueue(
        granted.context(), granted.transactionGeneration(),
        2, 1, request, 0, grantedLane, grantedHandle, detail));
    assertEquals(StatusCode.OK, locks.release(
        owner.context(), owner.transactionGeneration(), ownerToken, detail));
    assertEquals(LockWaitState.GRANTED, grantedHandle.state());
    assertEquals(StatusCode.OK, manager.abort(granted, new TransactionOutcome()));
    assertEquals(LockWaitState.CANCELLED, grantedHandle.state());
    assertEquals(StatusCode.CANCELLED, locks.await(grantedLane, grantedHandle, detail));
    assertEquals(0, manager.activeLockCount());
    assertEquals(0, manager.waitingLockCount());
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
  }

  @Test
  void boundedWaitResumesAtLockBoundaryWithoutRestartingTransaction() throws Exception {
    TransactionManager manager = new TransactionManager(
        7, 9, 2, 2, lockMemory(), 1_000_000_000L);
    Transaction owner = new Transaction(2);
    Transaction waiter = new Transaction(2);
    LockToken ownerToken = new LockToken();
    LockToken waiterToken = new LockToken();
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.READ_COMMITTED, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.READ_COMMITTED, 1, waiter));
    assertEquals(StatusCode.OK, manager.tryAcquireKey(owner, 3, 5, ownerToken));
    CountDownLatch started = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> acquired = executor.submit(() -> {
        started.countDown();
        return acquireReactive(manager, waiter, new LockRequest().setKey(
            3, 5, LockMode.EXCLUSIVE,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(1)), waiterToken);
      });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + 1_000_000_000L;
      while (manager.waitingLockCount() == 0 && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(1, manager.waitingLockCount());
      assertEquals(StatusCode.OK, manager.release(owner, ownerToken));
      assertEquals(StatusCode.OK, acquired.get(1, TimeUnit.SECONDS));
      assertTrue(waiterToken.isActive());
      assertEquals(StatusCode.OK, manager.release(waiter, waiterToken));
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(waiter, new TransactionOutcome()));
  }

  @Test
  void boundedWaitTimesOutAndRecyclesWaiterSlot() {
    TransactionManager manager = new TransactionManager(
        11, 12, 2, 2, lockMemory(), 1_000_000L);
    Transaction owner = new Transaction(2);
    Transaction waiter = new Transaction(2);
    LockToken ownerToken = new LockToken();
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.READ_COMMITTED, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.READ_COMMITTED, 1, waiter));
    assertEquals(StatusCode.OK, manager.tryAcquireKey(owner, 3, 5, ownerToken));
    assertEquals(StatusCode.TIMEOUT, acquireReactive(
        manager, waiter, new LockRequest().setKey(
            3, 5, LockMode.EXCLUSIVE, System.nanoTime() + 1_000_000L),
        new LockToken()));
    assertEquals(0, manager.waitingLockCount());
    assertEquals(StatusCode.OK, manager.release(owner, ownerToken));
    LockToken resumed = new LockToken();
    assertEquals(StatusCode.OK, manager.tryAcquireKey(waiter, 3, 5, resumed));
    assertEquals(StatusCode.OK, manager.release(waiter, resumed));
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(waiter, new TransactionOutcome()));
  }

  @Test
  void timeoutAndInterruptLeaveTheSameExecutionLaneReusable() {
    TransactionManager manager = new TransactionManager(
        13, 14, 2, 2, lockMemory(), 1_000_000L);
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(64);
    Transaction owner = new Transaction(2);
    Transaction waiter = new Transaction(2);
    LockToken ownerToken = new LockToken();
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.READ_COMMITTED, 1, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.READ_COMMITTED, 1, waiter));
    assertEquals(StatusCode.OK, manager.tryAcquireKey(owner, 3, 6, ownerToken));

    LockRequest timeout = new LockRequest().setKey(
        3, 6, LockMode.EXCLUSIVE, System.nanoTime() + 1_000_000L);
    assertEquals(StatusCode.RETRY, locks.enqueue(
        waiter.context(), waiter.transactionGeneration(), 0, 1,
        timeout, System.nanoTime(), lane, handle, detail));
    assertEquals(StatusCode.TIMEOUT, locks.await(lane, handle, detail));
    assertFalse(lane.isPending());
    assertEquals(StatusCode.OK, lane.reset());
    assertEquals(StatusCode.OK, handle.reset());

    LockRequest indefinite = new LockRequest().setKey(
        3, 6, LockMode.EXCLUSIVE, 0);
    assertEquals(StatusCode.RETRY, locks.enqueue(
        waiter.context(), waiter.transactionGeneration(), 0, 2,
        indefinite, System.nanoTime(), lane, handle, detail));
    Thread.currentThread().interrupt();
    try {
      assertEquals(StatusCode.CANCELLED, locks.await(lane, handle, detail));
      assertFalse(lane.isPending());
    } finally {
      Thread.interrupted();
    }
    assertEquals(StatusCode.OK, lane.reset());
    assertEquals(StatusCode.OK, handle.reset());

    assertEquals(StatusCode.RETRY, locks.enqueue(
        waiter.context(), waiter.transactionGeneration(), 0, 3,
        indefinite, System.nanoTime(), lane, handle, detail));
    assertEquals(StatusCode.OK, manager.release(owner, ownerToken));
    Thread.currentThread().interrupt();
    try {
      assertEquals(StatusCode.OK, locks.await(lane, handle, detail));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    LockToken granted = new LockToken();
    assertEquals(StatusCode.OK, locks.consume(
        waiter.context(), waiter.transactionGeneration(), lane, handle, granted, detail));
    assertEquals(StatusCode.OK, manager.release(waiter, granted));
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(waiter, new TransactionOutcome()));
  }

  @Test
  void lockMemoryEnvelopeUsesA64BitByteBudget() {
    assertEquals(8L << 20, lockMemory().maximumBytes());
  }

  @Test
  void capturesActiveSetAndPublishesCommitAtomically() {
    TransactionManager manager = new TransactionManager(11, 13, 2, 4);
    Transaction first = new Transaction(4);
    Transaction second = new Transaction(4);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.REPEATABLE_READ, 7, first));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.REPEATABLE_READ, 7, second));
    assertEquals(0, first.snapshot().activeTransactionCount());
    assertEquals(1, second.snapshot().activeTransactionCount());
    assertTrue(second.snapshot().excludesTransaction(first.transactionId()));
    assertFalse(first.snapshot().excludesTransaction(second.transactionId()));

    FakeParticipant participant = new FakeParticipant();
    participant.set(StatusCode.OK, 8);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, manager.commit(first, participant, outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    assertEquals(8, outcome.commitSequence());
    assertEquals(1, manager.activeTransactionCount());
    assertEquals(StatusCode.OK, manager.abort(second, outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(0, manager.activeTransactionCount());
  }

  @Test
  void refreshesReadCommittedAndBoundsActiveTransactions() {
    TransactionManager manager = new TransactionManager(17, 19, 2, 1);
    Transaction transaction = new Transaction(1);
    Transaction overflow = new Transaction(1);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.READ_COMMITTED, 3, transaction));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        manager.begin(IsolationLevel.READ_COMMITTED, 3, overflow));
    assertEquals(StatusCode.OK, manager.refreshReadCommitted(transaction, 4));
    assertEquals(4, transaction.snapshot().visibleCommitSequence());
    assertEquals(
        StatusCode.CONFLICT,
        manager.refreshReadCommitted(transaction, 2));
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
  }

  @Test
  void serializableRefreshCapturesFrontierAfterProtection() {
    TransactionManager manager = new TransactionManager(19, 23, 2, 2);
    Transaction transaction = new Transaction(2);
    Transaction repeatableRead = new Transaction(2);
    assertEquals(
        StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 3, transaction));
    assertEquals(
        StatusCode.OK,
        manager.refreshSerializableAfterProtection(transaction, () -> 7));
    assertEquals(7, transaction.snapshot().visibleCommitSequence());
    assertEquals(
        StatusCode.OK,
        manager.begin(IsolationLevel.REPEATABLE_READ, 7, repeatableRead));
    assertEquals(
        StatusCode.CONFLICT,
        manager.refreshSerializableAfterProtection(repeatableRead, () -> 8));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, manager.abort(transaction, outcome));
    assertEquals(StatusCode.OK, manager.abort(repeatableRead, outcome));
  }

  @Test
  void distinguishesPrecommitConflictFromIndeterminateFailure() {
    TransactionManager manager = new TransactionManager(23, 29, 2, 2);
    Transaction transaction = new Transaction(2);
    TransactionOutcome outcome = new TransactionOutcome();
    FakeParticipant participant = new FakeParticipant();
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, transaction));
    participant.set(StatusCode.CONFLICT, 0);
    assertEquals(StatusCode.CONFLICT, manager.commit(transaction, participant, outcome));
    assertEquals(TransactionState.ABORTED, transaction.state());

    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, transaction));
    participant.set(StatusCode.IO_FAILURE, 0);
    assertEquals(StatusCode.IO_FAILURE, manager.commit(transaction, participant, outcome));
    assertEquals(TransactionState.INDETERMINATE, transaction.state());
  }

  @Test
  void maintenanceRequiresQuiescenceAndPublishesThroughSameBarrier() {
    TransactionManager manager = new TransactionManager(31, 37, 2, 2);
    Transaction active = new Transaction(2);
    TransactionOutcome outcome = new TransactionOutcome();
    FakeParticipant participant = new FakeParticipant();
    participant.set(StatusCode.OK, 4);
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.REPEATABLE_READ, 3, active));
    assertEquals(StatusCode.RETRY, manager.commitMaintenance(participant, outcome));
    assertFalse(outcome.isAvailable());
    assertEquals(StatusCode.OK, manager.abort(active, outcome));
    assertEquals(StatusCode.OK, manager.commitMaintenance(participant, outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    assertEquals(3, outcome.transactionId());
    assertEquals(4, outcome.commitSequence());
  }

  @Test
  void orderedKeyLocksAdmitExtremaAndKeepSpacesIndependent() {
    TransactionManager manager = new TransactionManager(41, 43, 2, 4);
    Transaction first = new Transaction(4);
    Transaction second = new Transaction(4);
    Transaction contender = new Transaction(4);
    LockToken firstKey = new LockToken();
    LockToken secondKey = new LockToken();
    LockToken blockedKey = new LockToken();
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, first));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, second));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, contender));
    assertEquals(StatusCode.OK, manager.tryAcquireKey(first, 7, Long.MAX_VALUE, firstKey));
    assertEquals(StatusCode.OK, manager.tryAcquireKey(second, 8, Long.MAX_VALUE, secondKey));
    assertEquals(
        StatusCode.RETRY,
        manager.tryAcquireKey(contender, 7, Long.MAX_VALUE, blockedKey));
    assertEquals(StatusCode.OK, manager.release(second, secondKey));
    assertEquals(StatusCode.OK, manager.release(first, firstKey));
    assertEquals(StatusCode.OK, manager.abort(first, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(second, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(contender, new TransactionOutcome()));
  }

  @Test
  void fullSpaceRangeEndsAtNextSpaceMinimum() {
    TransactionManager manager = new TransactionManager(47, 53, 2, 4);
    Transaction reader = new Transaction(4);
    Transaction inside = new Transaction(4);
    Transaction adjacent = new Transaction(4);
    LockToken range = new LockToken();
    LockToken insideKey = new LockToken();
    LockToken adjacentKey = new LockToken();
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, reader));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, inside));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 1, adjacent));
    assertEquals(
        StatusCode.OK,
        manager.tryAcquireSharedRange(
            reader, 9, Long.MIN_VALUE, 10, Long.MIN_VALUE, range));
    assertEquals(
        StatusCode.RETRY,
        manager.tryAcquireKey(inside, 9, Long.MAX_VALUE, insideKey));
    assertEquals(
        StatusCode.OK,
        manager.tryAcquireKey(adjacent, 10, Long.MIN_VALUE, adjacentKey));
    assertEquals(StatusCode.OK, manager.release(adjacent, adjacentKey));
    assertEquals(StatusCode.OK, manager.release(reader, range));
    assertEquals(StatusCode.OK, manager.abort(reader, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(inside, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(adjacent, new TransactionOutcome()));
  }

  @Test
  void byteEnvelopeAllowsMoreThanLegacyPerOwnerDerivation() {
    TransactionManager manager = new TransactionManager(59, 61, 2, 1, lockMemory());
    Transaction transaction = new Transaction(1);
    LockToken[] tokens = new LockToken[130];
    assertEquals(StatusCode.OK,
        manager.begin(IsolationLevel.SERIALIZABLE, 1, transaction));
    for (int index = 0; index < tokens.length; index++) {
      tokens[index] = new LockToken();
      assertEquals(StatusCode.OK,
          manager.tryAcquireKey(transaction, 17, index, tokens[index]));
    }
    assertEquals(130, manager.activeLockCount());
    LockToken beyondLegacyBoundary = new LockToken();
    assertEquals(StatusCode.OK,
        manager.tryAcquireKey(transaction, 17, 131, beyondLegacyBoundary));
    assertEquals(StatusCode.OK, manager.release(transaction, beyondLegacyBoundary));
    for (LockToken token : tokens) {
      assertEquals(StatusCode.OK, manager.release(transaction, token));
    }
    assertEquals(StatusCode.OK, manager.abort(transaction, new TransactionOutcome()));
  }

  private static LockMemoryEnvelope lockMemory() {
    return new LockMemoryEnvelope(8L << 20);
  }

  private static StatusCode acquireReactive(
      TransactionManager manager, Transaction transaction,
      LockRequest request, LockToken token) {
    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(32);
    long generation = transaction.transactionGeneration();
    long now = System.nanoTime();
    StatusCode status = locks.tryAcquire(
        transaction.context(), generation, request, now, token, detail);
    if (status != StatusCode.RETRY) return status;
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    status = locks.enqueue(
        transaction.context(), generation, 0, 1,
        request, now, lane, handle, detail);
    if (status == StatusCode.RETRY) status = locks.await(lane, handle, detail);
    else if (!status.isOk() && handle.state() != LockWaitState.IDLE) {
      status = locks.await(lane, handle, detail);
    }
    return status.isOk()
        ? locks.consume(transaction.context(), generation, lane, handle, token, detail)
        : status;
  }

  private static boolean parked(Thread thread) {
    return thread.getState() == Thread.State.WAITING
        || thread.getState() == Thread.State.TIMED_WAITING;
  }

  private static final class FakeParticipant implements TransactionCommitParticipant {
    private StatusCode status = StatusCode.OK;
    private long sequence;
    private int calls;

    void set(StatusCode commitStatus, long commitSequence) {
      status = commitStatus;
      sequence = commitSequence;
    }

    @Override
    public StatusCode commit(long transactionId) {
      calls++;
      return transactionId > 0 ? status : StatusCode.INVALID_EXTERNAL_INPUT;
    }

    @Override
    public long committedSequence() {
      return sequence;
    }
  }

  private static final class AdmissionSource implements TransactionAdmissionSource {
    private StatusCode status;
    private long sequence;
    private int admissionCalls;

    @Override
    public StatusCode transactionAdmissionStatus() {
      admissionCalls++;
      return status;
    }

    @Override
    public long currentCommitSequence() { return sequence; }
  }

  private static final class BlockingParticipant implements TransactionCommitParticipant {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final long sequence;

    BlockingParticipant(long commitSequence) { sequence = commitSequence; }

    @Override
    public StatusCode commit(long transactionId) {
      entered.countDown();
      try {
        return release.await(5, TimeUnit.SECONDS) ? StatusCode.OK : StatusCode.TIMEOUT;
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        return StatusCode.CANCELLED;
      }
    }

    @Override
    public long committedSequence() { return sequence; }
  }

  private static final class BlockingQuiescentParticipant
      implements TransactionQuiescentParticipant {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public StatusCode execute() {
      entered.countDown();
      try {
        return release.await(5, TimeUnit.SECONDS) ? StatusCode.OK : StatusCode.TIMEOUT;
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        return StatusCode.CANCELLED;
      }
    }
  }
}
