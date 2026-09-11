package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class TransactionManagerTestSupport {
  static LockMemoryEnvelope lockMemory() {
    return new LockMemoryEnvelope(8L << 20);
  }

  static StatusCode acquireReactive(
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

  static boolean parked(Thread thread) {
    return thread.getState() == Thread.State.WAITING
        || thread.getState() == Thread.State.TIMED_WAITING;
  }

  static final class FakeParticipant implements TransactionCommitParticipant {
    StatusCode status = StatusCode.OK;
    long sequence;
    int calls;

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

  static final class AdmissionSource implements TransactionAdmissionSource {
    StatusCode status;
    long sequence;
    int admissionCalls;

    @Override
    public StatusCode transactionAdmissionStatus() {
      admissionCalls++;
      return status;
    }

    @Override
    public long currentCommitSequence() { return sequence; }
  }

  static final class BlockingParticipant implements TransactionCommitParticipant {
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

  static final class BlockingQuiescentParticipant
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
