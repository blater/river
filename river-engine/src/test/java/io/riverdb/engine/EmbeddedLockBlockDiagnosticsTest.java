package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.LockBlockCausalitySnapshot;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.lock.LockExecutionLane;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.lock.LockWaitHandle;
import org.junit.jupiter.api.Test;

final class EmbeddedLockBlockDiagnosticsTest {
  @Test
  void emitsOneReconciledActiveOwnerBucketAndTerminalSnapshotGauge() {
    TransactionManager manager = new TransactionManager(11, 13, 1, 2);
    Transaction owner = new Transaction(2);
    Transaction waiter = new Transaction(2);
    assertEquals(StatusCode.OK, manager.beginLockBlockCausalityCapture());
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 0, owner));
    assertEquals(StatusCode.OK, manager.begin(IsolationLevel.SERIALIZABLE, 0, waiter));

    LockService locks = manager.lockService();
    StatusDetail detail = new StatusDetail(64);
    LockRequest request = new LockRequest().setExact(
        LockScope.ROW, 17, 19, LockMode.EXCLUSIVE, 0);
    LockToken ownerToken = new LockToken();
    LockToken grantedToken = new LockToken();
    LockExecutionLane lane = new LockExecutionLane();
    LockWaitHandle handle = new LockWaitHandle();
    assertEquals(StatusCode.OK, locks.tryAcquire(
        owner.context(), owner.transactionGeneration(), request, 0, ownerToken, detail));
    assertEquals(StatusCode.RETRY, locks.enqueue(
        waiter.context(), waiter.transactionGeneration(), 1, 1,
        request, 0, lane, handle, detail));
    assertEquals(StatusCode.OK, locks.release(
        owner.context(), owner.transactionGeneration(), ownerToken, detail));
    assertEquals(StatusCode.OK, locks.await(lane, handle, detail));
    assertEquals(StatusCode.OK, locks.consume(
        waiter.context(), waiter.transactionGeneration(), lane, handle, grantedToken, detail));
    assertEquals(StatusCode.OK, locks.release(
        waiter.context(), waiter.transactionGeneration(), grantedToken, detail));
    assertEquals(StatusCode.OK, manager.abort(owner, new TransactionOutcome()));
    assertEquals(StatusCode.OK, manager.abort(waiter, new TransactionOutcome()));
    assertEquals(0, manager.retainedSnapshotCount());

    LockBlockCausalitySnapshot snapshot = manager.newLockBlockCausalitySnapshot();
    assertEquals(StatusCode.OK, manager.endLockBlockCausalityCapture(snapshot));
    StringBuilder target = new StringBuilder();
    EmbeddedLockBlockDiagnostics.append(target, snapshot, manager.retainedSnapshotCount());
    String metrics = target.toString();
    assertTrue(metrics.contains("server_capture_retained_snapshots=0\n"));
    assertTrue(metrics.contains("server_capture_lock_block_valid=true\n"));
    assertTrue(metrics.contains("server_capture_lock_block_bucket_total=1\n"));
    assertTrue(metrics.contains("server_capture_lock_block_bucket_count=1\n"));
    assertTrue(metrics.contains("server_capture_lock_block_bucket_0_scope=ROW\n"));
    assertTrue(metrics.contains("server_capture_lock_block_bucket_0_requested_mode=EXCLUSIVE\n"));
    assertTrue(metrics.contains("server_capture_lock_block_bucket_0_blocker_mode=EXCLUSIVE\n"));
    assertTrue(metrics.contains("server_capture_lock_block_bucket_0_waiter_queue=ORDINARY\n"));
    assertTrue(metrics.contains("server_capture_lock_block_bucket_0_blocker_queue=ACTIVE_OWNER\n"));
    assertTrue(metrics.contains(
        "server_capture_lock_block_bucket_0_relationship=ACTIVE_OWNER\n"));
    assertTrue(metrics.contains(
        "server_capture_lock_block_bucket_0_grant_precondition="
            + "NO_INCOMPATIBLE_ACTIVE_OWNER\n"));
    assertTrue(metrics.contains("server_capture_lock_block_bucket_0_count=1\n"));
  }
}
