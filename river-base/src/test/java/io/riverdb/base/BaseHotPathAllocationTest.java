package io.riverdb.base;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.CloseGuard;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.concurrent.OwnershipGuard;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class BaseHotPathAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedStatusCancellationAndGuardPathsDoNotAllocatePerCall() {
    ThreadMXBean bean = allocationBean();
    StatusDetail detail = new StatusDetail(64);
    MutableCancellationToken cancellation = new MutableCancellationToken();
    CloseGuard close = CloseGuard.enabled();
    OwnershipGuard ownership = OwnershipGuard.ownedBy(7);
    FatalStateFence fatal = new FatalStateFence();

    exercise(detail, cancellation, close, ownership, fatal, 100_000);

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    exercise(detail, cancellation, close, ownership, fatal, 1_000_000);
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertTrue(
        allocated <= 256,
        "warmed base status and guard calls allocated more than measurement noise: " + allocated);
  }

  private static void exercise(
      StatusDetail detail,
      MutableCancellationToken cancellation,
      CloseGuard close,
      OwnershipGuard ownership,
      FatalStateFence fatal,
      int iterations) {
    for (int index = 0; index < iterations; index++) {
      detail.reset().set(StatusCode.RETRY).append("sequence=").append(index);
      allocationGuard += detail.length();
      allocationGuard += detail.code().stableCode();
      allocationGuard += cancellation.status().stableCode();
      allocationGuard += close.checkOpen().stableCode();
      allocationGuard += ownership.checkOwnedBy(7).stableCode();
      allocationGuard += fatal.admissionStatus().stableCode();
    }
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
