package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalWalAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedReserveEncodeWriteAndForceReuseProductionCarriers(@TempDir Path root) {
    ThreadMXBean bean = allocationBean();
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            4,
            directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    LocalWalOpenResult open = new LocalWalOpenResult();
    assertEquals(
        StatusCode.OK,
        LocalWal.open(
            directory,
            DatabaseIncarnation.of(1, 2),
            WalGeneration.of(1),
            open));
    LocalWal wal = open.wal();
    LocalWalReservation reservation = new LocalWalReservation();
    LocalWalAppendResult appended = new LocalWalAppendResult();

    for (int index = 0; index < 100; index++) {
      exercise(wal, reservation, appended, index);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 100; index < 300; index++) {
      exercise(wal, reservation, appended, index);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertEquals(0, wal.copiedPayloadBytes());
    assertTrue(
        allocated <= 512,
        "warmed production WAL path allocated bytes: " + allocated);
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void exercise(
      LocalWal wal,
      LocalWalReservation reservation,
      LocalWalAppendResult appended,
      long value) {
    allocationGuard += wal.reserve(Long.BYTES, reservation).ordinal();
    reservation.writablePayload().putLong(value);
    allocationGuard += wal.publish(reservation, value, 0, 0, 1, 1, appended).ordinal();
    allocationGuard += appended.endOffset();
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(bean instanceof ThreadMXBean);
    ThreadMXBean allocationBean = (ThreadMXBean) bean;
    Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
    allocationBean.setThreadAllocatedMemoryEnabled(true);
    return allocationBean;
  }
}
