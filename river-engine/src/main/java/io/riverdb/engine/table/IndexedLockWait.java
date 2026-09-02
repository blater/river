package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockDeadline;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;
import java.nio.ByteBuffer;

/** Reusable serial execution lane that parks at one lock boundary without polling or retrying SQL. */
final class IndexedLockWait {
  private final LockService locks;
  private final long timeoutNanos;
  private final LockRequest request = new LockRequest();
  private final LockExecutionLane lane = new LockExecutionLane();
  private final LockWaitHandle handle = new LockWaitHandle();
  private final LockToken acquired = new LockToken();
  private final StatusDetail detail = new StatusDetail(64);
  private final StatusDetail cancellationDetail = new StatusDetail(64);
  private long laneGeneration = 1;

  IndexedLockWait(TransactionManager manager) {
    locks = manager.lockService();
    timeoutNanos = manager.lockWaitTimeoutNanos();
  }

  StatusCode acquireKey(Transaction transaction, long space, long key, LockMode mode) {
    long now = System.nanoTime();
    request.setKey(space, key, mode, 0).waitUntil(deadline(now));
    return acquireRetained(transaction, now, true);
  }

  StatusCode acquireBorrowedKey(
      Transaction transaction, long space, long key, LockMode mode, LockToken token) {
    if (token == null || token.isActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
    long now = System.nanoTime();
    request.setKey(space, key, mode, 0).waitUntil(deadline(now));
    return acquire(transaction, now, true, token);
  }

  StatusCode retain(Transaction transaction, LockToken token) {
    return locks.retain(
        transaction.context(), transaction.transactionGeneration(), token, detail);
  }

  StatusCode release(Transaction transaction, LockToken token) {
    StatusCode status = locks.release(
        transaction.context(), transaction.transactionGeneration(), token, detail);
    // Transaction-wide deadlock cleanup may revoke the holding before its borrower unwinds.
    return status == StatusCode.NOT_OWNER && !token.isActive() ? StatusCode.OK : status;
  }

  StatusCode tryAcquireKey(Transaction transaction, long space, long key, LockMode mode) {
    long now = System.nanoTime();
    request.setKey(space, key, mode, 0).waitUntil(deadline(now));
    return acquireRetained(transaction, now, false);
  }

  StatusCode acquireRange(
      Transaction transaction,
      long lowerSpace, long lowerKey, long upperSpace, long upperKey,
      LockMode mode) {
    long now = System.nanoTime();
    request.setRange(lowerSpace, lowerKey, upperSpace, upperKey, mode, 0)
        .waitUntil(deadline(now));
    return acquireRetained(transaction, now, true);
  }

  StatusCode acquireTupleKey(
      Transaction transaction, long namespace,
      ByteBuffer key, int offset, int length, LockMode mode) {
    long now = System.nanoTime();
    request.setTupleKey(namespace, key, offset, length, mode, 0).waitUntil(deadline(now));
    return acquireRetained(transaction, now, true);
  }

  StatusCode acquireBorrowedTupleKey(
      Transaction transaction, long namespace,
      ByteBuffer key, int offset, int length, LockMode mode, LockToken token) {
    if (token == null || token.isActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
    long now = System.nanoTime();
    request.setTupleKey(namespace, key, offset, length, mode, 0).waitUntil(deadline(now));
    return acquire(transaction, now, true, token);
  }

  StatusCode tryAcquireTupleKey(
      Transaction transaction, long namespace,
      ByteBuffer key, int offset, int length, LockMode mode) {
    long now = System.nanoTime();
    request.setTupleKey(namespace, key, offset, length, mode, 0).waitUntil(deadline(now));
    return acquireRetained(transaction, now, false);
  }

  StatusCode acquireTupleRange(
      Transaction transaction, long namespace,
      ByteBuffer lower, int lowerOffset, int lowerLength, boolean lowerInclusive,
      ByteBuffer upper, int upperOffset, int upperLength, boolean upperInclusive,
      LockMode mode) {
    long now = System.nanoTime();
    request.setTupleRange(
        namespace,
        lower, lowerOffset, lowerLength, lowerInclusive,
        upper, upperOffset, upperLength, upperInclusive,
        mode, 0).waitUntil(deadline(now));
    return acquireRetained(transaction, now, true);
  }

  StatusCode holdsKey(Transaction transaction, long space, long key, LockMode mode) {
    request.setKey(space, key, mode, 0);
    return locks.holds(
        transaction.context(), transaction.transactionGeneration(), request, detail);
  }

  StatusCode holdsTupleKey(
      Transaction transaction, long namespace,
      ByteBuffer key, int offset, int length, LockMode mode) {
    request.setTupleKey(namespace, key, offset, length, mode, 0);
    return locks.holds(
        transaction.context(), transaction.transactionGeneration(), request, detail);
  }

  StatusCode cancel(Transaction transaction) {
    if (!transaction.isActiveHandle()) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!lane.isPending()) return StatusCode.OK;
    StatusCode status = locks.cancel(lane, handle, cancellationDetail);
    return status == StatusCode.CANCELLED || status == StatusCode.NOT_OWNER
        ? StatusCode.OK : status;
  }

  private StatusCode acquireRetained(Transaction transaction, long now, boolean wait) {
    StatusCode status = acquire(transaction, now, wait, acquired);
    if (status.isOk()) status = retain(transaction, acquired);
    if (acquired.isActive()) {
      StatusCode release = release(transaction, acquired);
      if (status.isOk()) status = release;
    }
    StatusCode reset = acquired.reset();
    return status.isOk() && !reset.isOk() ? reset : status;
  }

  private StatusCode acquire(
      Transaction transaction, long now, boolean wait, LockToken token) {
    long generation = transaction.transactionGeneration();
    StatusCode status = locks.tryAcquire(
        transaction.context(), generation, request, now, token, detail);
    if (status == StatusCode.RETRY && wait) {
      if (laneGeneration <= 0) return StatusCode.RESOURCE_EXHAUSTED;
      status = locks.enqueue(
          transaction.context(), generation, 0, laneGeneration,
          request, now, lane, handle, detail);
      if (status == StatusCode.RETRY) status = locks.await(lane, handle, detail);
      else if (!status.isOk() && handle.state() != LockWaitState.IDLE) {
        status = locks.await(lane, handle, detail);
      }
      if (status.isOk()) {
        status = locks.consume(
            transaction.context(), generation, lane, handle, token, detail);
      }
      StatusCode reset = resetLane();
      if (status.isOk() && !reset.isOk()) status = reset;
    }
    return status;
  }

  private StatusCode resetLane() {
    StatusCode laneStatus = lane.reset();
    StatusCode handleStatus = handle.reset();
    if (laneStatus.isOk() && handleStatus.isOk()) {
      laneGeneration = laneGeneration == Long.MAX_VALUE ? 0 : laneGeneration + 1;
      return StatusCode.OK;
    }
    return StatusCode.INVARIANT_BROKEN;
  }

  private long deadline(long now) {
    return LockDeadline.after(now, timeoutNanos);
  }
}
