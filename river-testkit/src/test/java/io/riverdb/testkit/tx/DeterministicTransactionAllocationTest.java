package io.riverdb.testkit.tx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.VisibilityResult;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;
import io.riverdb.tx.api.version.VersionPointer;
import io.riverdb.tx.api.version.VersionRecord;
import io.riverdb.tx.spi.RecoveryTransactionView;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class DeterministicTransactionAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedLookupVisibilityReadAndLockLifecycleUseCallerOwnedCarriers() {
    ThreadMXBean bean = allocationBean();
    DeterministicTransactionProvider provider =
        new DeterministicTransactionProvider(1, 2, 3, 2, 1, 8, 1);
    StatusDetail detail = new StatusDetail(0);
    RecoveryTransactionView recovery = new RecoveryTransactionView().set(
        1, 2, 1, TransactionState.ACTIVE, 1, 1, 0, 0, 0);
    allocationGuard += provider.storeRecoveryView(recovery, detail).ordinal();
    DeterministicSnapshot snapshot = new DeterministicSnapshot(
        1, 2, 1, 7, new long[] {1}, 1);
    TransactionContext context = new TransactionContext(
        1, 2, 1, IsolationLevel.REPEATABLE_READ, snapshot, CancellationToken.NONE);
    VersionRecord append = new VersionRecord().set(1, 0, 0, 0, new byte[] {1}, 0, 1);
    VersionPointer pointer = new VersionPointer();
    allocationGuard += provider.appendVersion(context, append, pointer, detail).ordinal();

    TransactionOutcome outcome = new TransactionOutcome();
    VisibilityResult visibility = new VisibilityResult();
    VersionRecord read = new VersionRecord();
    LockRequest request = new LockRequest().set(
        LockScope.KEY, 4, 5, LockMode.EXCLUSIVE, 0);
    LockToken token = new LockToken();
    for (int index = 0; index < 1_000; index++) {
      exercise(provider, context, pointer, outcome, visibility, read, request, token, detail);
    }

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 10_000; index++) {
      exercise(provider, context, pointer, outcome, visibility, read, request, token, detail);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertTrue(read.payloadLength() == 1);
    assertTrue(
        allocated <= 256,
        "warmed transaction contract path allocated more than measurement noise: " + allocated);
  }

  private static void exercise(
      DeterministicTransactionProvider provider,
      TransactionContext context,
      VersionPointer pointer,
      TransactionOutcome outcome,
      VisibilityResult visibility,
      VersionRecord read,
      LockRequest request,
      LockToken token,
      StatusDetail detail) {
    allocationGuard += provider.lookupOutcome(1, 2, 1, outcome, detail).ordinal();
    allocationGuard += provider.resolve(context, 2, 6, visibility, detail).ordinal();
    allocationGuard += provider.readVersion(pointer, read, detail).ordinal();
    allocationGuard += provider.tryAcquire(context, request, 1, token, detail).ordinal();
    allocationGuard += provider.release(token, detail).ordinal();
    allocationGuard += token.reset().ordinal();
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }
}
