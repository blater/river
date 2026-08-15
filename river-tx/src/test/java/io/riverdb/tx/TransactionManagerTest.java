package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.lock.LockToken;
import org.junit.jupiter.api.Test;

final class TransactionManagerTest {
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
    assertEquals(StatusCode.OK, manager.release(secondKey));
    assertEquals(StatusCode.OK, manager.release(firstKey));
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
    assertEquals(StatusCode.OK, manager.release(adjacentKey));
    assertEquals(StatusCode.OK, manager.release(range));
    assertEquals(StatusCode.OK, manager.abort(reader, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(inside, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(adjacent, new TransactionOutcome()));
  }

  private static final class FakeParticipant implements TransactionCommitParticipant {
    private StatusCode status = StatusCode.OK;
    private long sequence;

    void set(StatusCode commitStatus, long commitSequence) {
      status = commitStatus;
      sequence = commitSequence;
    }

    @Override
    public StatusCode commit(long transactionId) {
      return transactionId > 0 ? status : StatusCode.INVALID_EXTERNAL_INPUT;
    }

    @Override
    public long committedSequence() {
      return sequence;
    }
  }
}
