package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.storage.heap.HeapScanCursor;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SinglePageTableTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(401, 409);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void insertsFetchesScansFlushesAndReopens(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    SinglePageStore store = createPageStore(directory, wal);
    SinglePageTable table = createTable(store);
    byte[] expected = {5, 8, 9, 7, 9, 3};
    HeapInsertResult inserted = new HeapInsertResult();
    assertEquals(
        StatusCode.OK,
        table.insert(2, ByteBuffer.wrap(expected), inserted));
    assertEquals(1, inserted.rowId());
    assertEquals(expected.length, table.copiedRowBytes());

    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetch(inserted.rowId(), fetched));
    assertRow(expected, fetched);
    HeapScanCursor scan = new HeapScanCursor();
    assertEquals(StatusCode.OK, table.next(scan, fetched));
    assertRow(expected, fetched);
    assertEquals(StatusCode.CONFLICT, table.next(scan, fetched));

    LocalWalReadResult record = lastWalRecord(wal);
    assertEquals(2, record.header().transactionId());
    assertEquals(2, record.header().commitSequence());
    assertEquals(1, record.header().decisionCode());

    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    store = openPageStore(directory, wal);
    table = openTable(store);
    assertEquals(1, table.rowCount());
    assertEquals(StatusCode.OK, table.fetch(1, fetched));
    assertRow(expected, fetched);
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void crashAfterCommitBeforePageFlushRecoversHeapRow(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    SinglePageTable table = createTable(createPageStore(directory, wal));
    byte[] expected = {3, 2, 3, 8, 4, 6};
    assertEquals(
        StatusCode.OK,
        table.insert(2, ByteBuffer.wrap(expected), new HeapInsertResult()));

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    table = openTable(openPageStore(directory, wal));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetch(1, fetched));
    assertRow(expected, fetched);
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void assertRow(byte[] expected, HeapRowResult row) {
    ByteBuffer actual = ByteBuffer.allocate(expected.length);
    assertEquals(StatusCode.OK, row.copyTo(actual));
    assertArrayEquals(expected, actual.array());
  }

  private static LocalWalReadResult lastWalRecord(LocalWal wal) {
    long offset = 64;
    LocalWalReadResult read = new LocalWalReadResult();
    while (offset < wal.tailEnd()) {
      assertEquals(StatusCode.OK, wal.read(offset, read));
      offset = read.nextOffset();
    }
    return read;
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            result));
    return result.directory();
  }

  private static LocalWal openWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static SinglePageStore createPageStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    SinglePageStoreOpenResult result = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.create(directory, wal, DATABASE, GENERATION, result));
    return result.store();
  }

  private static SinglePageStore openPageStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    SinglePageStoreOpenResult result = new SinglePageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        SinglePageStore.open(directory, wal, DATABASE, GENERATION, result));
    return result.store();
  }

  private static SinglePageTable createTable(SinglePageStore store) {
    SinglePageTableOpenResult result = new SinglePageTableOpenResult();
    assertEquals(StatusCode.OK, SinglePageTable.create(store, result));
    return result.table();
  }

  private static SinglePageTable openTable(SinglePageStore store) {
    SinglePageTableOpenResult result = new SinglePageTableOpenResult();
    assertEquals(StatusCode.OK, SinglePageTable.open(store, result));
    return result.table();
  }
}
