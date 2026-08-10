package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalWalAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedAppendAndReadReuseProductionCarriers(@TempDir Path root) {
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
    LocalWalReadResult read = new LocalWalReadResult();

    for (int index = 0; index < 100; index++) {
      exercise(wal, reservation, appended, index);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 100; index < 300; index++) {
      exercise(wal, reservation, appended, index);
    }
    long appendAllocated = bean.getThreadAllocatedBytes(threadId) - before;

    long readOffset = appended.startOffset();
    for (int index = 0; index < 100; index++) {
      exerciseRead(wal, readOffset, read);
    }
    ByteBuffer payloadIdentity = read.payload();
    before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 200; index++) {
      exerciseRead(wal, readOffset, read);
    }
    long readAllocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertEquals(0, wal.copiedPayloadBytes());
    assertTrue(
        appendAllocated <= 512,
        "warmed production WAL append allocated bytes: " + appendAllocated);
    assertTrue(
        readAllocated <= 512,
        "warmed production WAL read allocated bytes: " + readAllocated);
    assertSame(payloadIdentity, read.payload());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void exerciseRead(
      LocalWal wal,
      long offset,
      LocalWalReadResult read) {
    allocationGuard += wal.read(offset, read).ordinal();
    allocationGuard += read.payload().getLong(0);
    allocationGuard += read.nextOffset();
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
