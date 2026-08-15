package io.riverdb.engine.table;

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
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapRowResult;
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
  void warmedWideRowsUseSecondHeapWithoutPerOperationAllocation(@TempDir Path root) {
    ThreadMXBean bean = allocationBean();
    DatabaseIncarnation database = DatabaseIncarnation.of(449, 457);
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
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(directory, wal, database, generation, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    HeapInsertResult inserted = new HeapInsertResult();
    HeapRowResult fetched = new HeapRowResult();
    ByteBuffer row = ByteBuffer.allocateDirect(256);
    for (int index = 0; index < row.capacity(); index++) {
      row.put(index, (byte) index);
    }
    for (int index = 0; index < 64; index++) {
      exerciseWide(table, row, inserted, index);
    }
    assertTrue(table.pageCount() >= 4, "wide rows did not allocate another heap page");

    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 64; index < 80; index++) {
      exerciseWide(table, row, inserted, index);
    }
    long insertAllocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(insertAllocated <= 512, "multipage insert allocated bytes: " + insertAllocated);

    for (int index = 0; index < 64; index++) {
      int rowId = 1 + index % 80;
      allocationGuard += table.fetchByKey( 0,10_000L + rowId - 1L, fetched).ordinal();
      allocationGuard += fetched.length();
    }
    before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 256; index++) {
      int rowId = 1 + index % 80;
      allocationGuard += table.fetchByKey( 0,10_000L + rowId - 1L, fetched).ordinal();
      allocationGuard += fetched.length();
    }
    long fetchAllocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(fetchAllocated <= 256, "multipage fetch allocated bytes: " + fetchAllocated);

    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

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
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(directory, wal, database, generation, storeResult));
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
    assertTrue(
        splitAllocated <= 1024,
        "structural leaf split allocated bytes: " + splitAllocated);

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
    assertEquals(0, manager.activeLockCount());

    for (int key = 400; key < 416; key += 2) {
      exerciseTwoWriteTransaction(session, row, outcome, key);
    }
    writeSetCopiedBefore = session.copiedWriteSetBytes();
    walCopiedBefore = table.walCopyBytes();
    long stagedBeforeBatch = table.stagedCopyBytes();
    before = bean.getThreadAllocatedBytes(threadId);
    for (int key = 416; key < 432; key += 2) {
      exerciseTwoWriteTransaction(session, row, outcome, key);
    }
    long batchAllocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertEquals(
        16L * Long.BYTES,
        session.copiedWriteSetBytes() - writeSetCopiedBefore);
    assertEquals(16L * Long.BYTES, table.walCopyBytes() - walCopiedBefore);
    assertEquals(stagedBeforeBatch, table.stagedCopyBytes());
    assertTrue(
        batchAllocated <= 512,
        "warmed two-write transaction allocated bytes: " + batchAllocated);
    assertEquals(0, manager.activeLockCount());

    for (int value = 500; value < 508; value++) {
      exerciseTwoUpdates(session, row, outcome, value);
    }
    writeSetCopiedBefore = session.copiedWriteSetBytes();
    walCopiedBefore = table.walCopyBytes();
    long stagedBeforeUpdate = table.stagedCopyBytes();
    before = bean.getThreadAllocatedBytes(threadId);
    for (int value = 508; value < 516; value++) {
      exerciseTwoUpdates(session, row, outcome, value);
    }
    long updateAllocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertEquals(
        16L * Long.BYTES,
        session.copiedWriteSetBytes() - writeSetCopiedBefore);
    assertEquals(16L * Long.BYTES, table.walCopyBytes() - walCopiedBefore);
    assertEquals(stagedBeforeUpdate, table.stagedCopyBytes());
    assertTrue(
        updateAllocated <= 512,
        "warmed two-update transaction allocated bytes: " + updateAllocated);
    assertEquals(0, manager.activeLockCount());
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
    allocationGuard += session.insert( 0,value, row).ordinal();
    allocationGuard += session.commit(outcome).ordinal();
    allocationGuard += outcome.commitSequence();
  }

  private static void exerciseTwoWriteTransaction(
      IndexedTransactionSession session,
      ByteBuffer row,
      TransactionOutcome outcome,
      int key) {
    allocationGuard += session.begin(IsolationLevel.REPEATABLE_READ).ordinal();
    row.putLong(0, key);
    row.position(0);
    row.limit(Long.BYTES);
    allocationGuard += session.insert( 0,key, row).ordinal();
    row.putLong(0, key + 1L);
    row.position(0);
    row.limit(Long.BYTES);
    allocationGuard += session.insert( 0,key + 1L, row).ordinal();
    allocationGuard += session.commit(outcome).ordinal();
    allocationGuard += outcome.commitSequence();
  }

  private static void exerciseTwoUpdates(
      IndexedTransactionSession session,
      ByteBuffer row,
      TransactionOutcome outcome,
      int value) {
    allocationGuard += session.begin(IsolationLevel.REPEATABLE_READ).ordinal();
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    allocationGuard += session.update( 0,416, row).ordinal();
    row.putLong(0, value + 1L);
    row.position(0);
    row.limit(Long.BYTES);
    allocationGuard += session.update( 0,417, row).ordinal();
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
    allocationGuard += table.insert(value + 2L, 0, value, row, inserted).ordinal();
    allocationGuard += inserted.rowId();
  }

  private static void exerciseWide(
      IndexedTable table,
      ByteBuffer row,
      HeapInsertResult inserted,
      int value) {
    row.putLong(0, value);
    row.position(0);
    row.limit(row.capacity());
    allocationGuard += table.insert(2L + value, 0, 10_000L + value, row, inserted).ordinal();
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
