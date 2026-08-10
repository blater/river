package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.page.IndexedPageStore;
import io.riverdb.engine.page.IndexedPageStoreOpenResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedCompactHeapIndexCommitReusesProductionState(@TempDir Path root) {
    ThreadMXBean bean = allocationBean();
    DatabaseIncarnation database = DatabaseIncarnation.of(439, 443);
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
    IndexedPageStoreOpenResult storeResult = new IndexedPageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedPageStore.create(directory, wal, database, generation, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    HeapInsertResult inserted = new HeapInsertResult();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    for (int index = 0; index < 64; index++) {
      exercise(table, row, inserted, index);
    }
    long walCopiedBefore = table.walCopyBytes();
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 64; index < 128; index++) {
      exercise(table, row, inserted, index);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertEquals(64L * Long.BYTES, table.walCopyBytes() - walCopiedBefore);
    assertTrue(allocated <= 512, "warmed indexed insert allocated bytes: " + allocated);

    for (int index = 128; index < 256; index++) {
      exercise(table, row, inserted, index);
    }
    long stagedBeforeSplit = table.stagedCopyBytes();
    walCopiedBefore = table.walCopyBytes();
    before = bean.getThreadAllocatedBytes(threadId);
    exercise(table, row, inserted, 256);
    long splitAllocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertEquals(
        3L * io.riverdb.format.page.PageCodec.PAGE_BYTES,
        table.stagedCopyBytes() - stagedBeforeSplit);
    assertEquals(
        5L * io.riverdb.format.page.PageCodec.PAGE_BYTES,
        table.walCopyBytes() - walCopiedBefore);
    assertTrue(splitAllocated <= 512, "leaf split allocated bytes: " + splitAllocated);

    TransactionManager manager = new TransactionManager(
        database.high(), database.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = new IndexedTransactionSession(manager, table, Long.BYTES);
    TransactionOutcome outcome = new TransactionOutcome();
    for (int index = 300; index < 332; index++) {
      exerciseTransaction(session, row, outcome, index);
    }
    long writeSetCopiedBefore = session.copiedWriteSetBytes();
    before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 332; index < 364; index++) {
      exerciseTransaction(session, row, outcome, index);
    }
    long transactionAllocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertEquals(
        32L * Long.BYTES,
        session.copiedWriteSetBytes() - writeSetCopiedBefore);
    assertTrue(
        transactionAllocated <= 512,
        "warmed transaction commit allocated bytes: " + transactionAllocated);
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void exerciseTransaction(
      IndexedTransactionSession session,
      ByteBuffer row,
      TransactionOutcome outcome,
      int value) {
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    allocationGuard += session.begin(IsolationLevel.REPEATABLE_READ).ordinal();
    allocationGuard += session.insert(value, row).ordinal();
    allocationGuard += session.commit(outcome).ordinal();
    allocationGuard += outcome.commitSequence();
  }

  private static void exercise(
      IndexedTable table,
      ByteBuffer row,
      HeapInsertResult inserted,
      int value) {
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    allocationGuard += table.insert(value + 2L, value, row, inserted).ordinal();
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
