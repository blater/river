package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.page.SinglePageStore;
import io.riverdb.engine.page.SinglePageStoreOpenResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SinglePageTableAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedHeapInsertWalCommitReusesProductionCarriers(@TempDir Path root) {
    ThreadMXBean bean = allocationBean();
    DatabaseIncarnation database = DatabaseIncarnation.of(419, 421);
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
    assertEquals(StatusCode.OK, LocalWal.open(directory, database, generation, walResult));
    LocalWal wal = walResult.wal();
    SinglePageStoreOpenResult storeResult = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.create(directory, wal, database, generation, storeResult));
    SinglePageStore store = storeResult.store();
    SinglePageTableOpenResult tableResult = new SinglePageTableOpenResult();
    assertEquals(StatusCode.OK, SinglePageTable.create(store, tableResult));
    SinglePageTable table = tableResult.table();
    HeapInsertResult inserted = new HeapInsertResult();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, 99);

    for (int index = 0; index < 100; index++) {
      exercise(table, row, inserted, index + 2L);
    }
    long copiedBefore = table.copiedRowBytes();
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 100; index < 300; index++) {
      exercise(table, row, inserted, index + 2L);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertEquals(200L * Long.BYTES, table.copiedRowBytes() - copiedBefore);
    assertTrue(allocated <= 512, "warmed heap insert allocated bytes: " + allocated);
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void exercise(
      SinglePageTable table,
      ByteBuffer row,
      HeapInsertResult inserted,
      long transactionId) {
    row.position(0);
    row.limit(Long.BYTES);
    allocationGuard += table.insert(transactionId, row, inserted).ordinal();
    allocationGuard += inserted.rowId();
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
