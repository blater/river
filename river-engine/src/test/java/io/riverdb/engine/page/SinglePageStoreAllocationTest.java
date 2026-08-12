package io.riverdb.engine.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SinglePageStoreAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedPageUpdateAndForcedWalReuseCarriers(@TempDir Path root) {
    ThreadMXBean bean = allocationBean();
    DatabaseIncarnation database = DatabaseIncarnation.of(307, 311);
    WalGeneration generation = WalGeneration.of(1);
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
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(
        StatusCode.OK,
        LocalWal.open(directory, database, generation, walResult));
    LocalWal wal = walResult.wal();
    SinglePageStoreOpenResult storeResult = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.create(directory, wal, database, generation, storeResult));
    SinglePageStore store = storeResult.store();
    PageUpdate update = new PageUpdate();

    for (int index = 0; index < 100; index++) {
      exercise(store, update, index);
    }
    long copiedBefore = store.copiedPayloadBytes();
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 100; index < 300; index++) {
      exercise(store, update, index);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertEquals(200L * PageCodec.PAGE_BYTES, store.copiedPayloadBytes() - copiedBefore);
    assertTrue(
        allocated <= 512,
        "warmed page-to-WAL path allocated bytes: " + allocated);
    assertEquals(StatusCode.OK, store.flush());
    assertEquals(StatusCode.OK, store.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void exercise(SinglePageStore store, PageUpdate update, long value) {
    allocationGuard += store.beginUpdate(Long.BYTES, update).ordinal();
    update.writablePayload().putLong(value);
    allocationGuard += store.commit(update).ordinal();
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
