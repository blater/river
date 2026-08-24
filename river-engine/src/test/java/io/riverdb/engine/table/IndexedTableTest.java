package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(431, 433);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void forcedWalDoesNotAdvancePublishedTableSnapshot(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    IndexedTable table = createTable(store);
    ByteBuffer rows = ByteBuffer.allocateDirect(Long.BYTES);
    rows.putLong(0, 770);
    HeapInsertResult inserted = new HeapInsertResult();
    PendingMutationBuffer mutations = new PendingMutationBuffer(1, Long.BYTES);
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 0, 77, 0, rows, 0, Long.BYTES);

    assertEquals(StatusCode.OK, store.beginPreparedInsertGroup());
    assertEquals(StatusCode.OK, store.preflightPreparedWrites(mutations));
    assertEquals(StatusCode.OK, store.finishPreparedInsertPreflight(1));
    assertEquals(
        StatusCode.OK,
        store.appendPreparedWrites(2, 2, mutations, inserted));
    assertEquals(StatusCode.OK, store.forcePreparedInserts());
    assertEquals(2, wal.currentCommitSequence());
    assertEquals(1, store.currentCommitSequence());
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,77, new HeapRowResult()));

    assertEquals(StatusCode.OK, store.publishForcedInserts());
    assertEquals(2, store.currentCommitSequence());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,77, fetched));
    assertEquals(770, rowValue(fetched));
    close(table, wal, directory);
  }

  @Test
  void insertsSplitsLooksUpAndReopens(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    HeapInsertResult inserted = new HeapInsertResult();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    int entries = BTreePage.MAX_ENTRIES + 1;
    for (int index = entries - 1; index >= 0; index--) {
      row.putLong(0, index * 10L);
      row.position(0);
      row.limit(Long.BYTES);
      assertEquals(StatusCode.OK, table.insert(index + 2L, 0, index * 10L, row, inserted));
    }
    assertEquals(entries, table.rowCount());
    assertEquals(5, table.pageCount());
    assertEquals(5, table.rootPageId());
    assertEquals(StatusCode.CONFLICT, table.insert(900, 0, 100, row, inserted));
    assertEquals(entries, table.rowCount());
    assertAllRows(table, entries);

    LocalWalReadResult record = lastWalRecord(wal);
    assertEquals(2, record.header().transactionId());
    assertEquals(entries + 1L, record.header().commitSequence());
    assertEquals(5, littleInt(record.payload(), 16));

    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    corruptRootPage(directory, 5);
    wal = openWal(directory);
    table = openTable(openStore(directory, wal));
    assertEquals(entries, table.rowCount());
    assertEquals(5, table.rootPageId());
    assertAllRows(table, entries);
    row.putLong(0, 99_999);
    row.position(0);
    row.limit(Long.BYTES);
    assertEquals(StatusCode.OK, table.insert(999, 0, 99_999, row, inserted));
    HeapRowResult reopenedInsert = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,99_999, reopenedInsert));
    assertEquals(99_999, rowValue(reopenedInsert));
    close(table, wal, directory);
  }

  @Test
  void persistsAndScansFullSignedPairsAcrossSpaces(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    HeapInsertResult inserted = new HeapInsertResult();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    int[] spaces = {3, 2, 3, 2};
    long[] keys = {Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE};
    for (int index = 0; index < keys.length; index++) {
      row.putLong(0, index + 1);
      row.position(0);
      row.limit(Long.BYTES);
      assertEquals(
          StatusCode.OK,
          table.insert(index + 2L, spaces[index], keys[index], row, inserted));
    }
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    table = openTable(openStore(directory, wal));
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult result = new IndexedScanResult();
    assertEquals(
        StatusCode.OK,
        table.beginScan(
            table.visibleCommitSequence(),
            2, Long.MIN_VALUE, 4, Long.MIN_VALUE, cursor));
    int[] expectedSpaces = {2, 2, 3, 3};
    long[] expectedKeys = {Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE};
    for (int index = 0; index < expectedKeys.length; index++) {
      assertEquals(StatusCode.OK, table.nextScan(cursor, result));
      assertEquals(expectedSpaces[index], result.keySpace());
      assertEquals(expectedKeys[index], result.key());
    }
    assertEquals(StatusCode.CONFLICT, table.nextScan(cursor, result));
    assertEquals(StatusCode.OK, table.closeScan(cursor));
    close(table, wal, directory);
  }

  @Test
  void recoversHeapIndexAndRootAfterSplitBeforePageFlush(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    HeapInsertResult inserted = new HeapInsertResult();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      row.putLong(0, index);
      row.position(0);
      row.limit(Long.BYTES);
      assertEquals(StatusCode.OK, table.insert(index + 2L, 0, index, row, inserted));
    }
    assertEquals(StatusCode.OK, table.flush());

    row.putLong(0, 10_000);
    row.position(0);
    row.limit(Long.BYTES);
    assertEquals(StatusCode.OK, table.insert(10_002, 0, 10_000, row, inserted));
    assertEquals(5, table.rootPageId());
    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    table = openTable(openStore(directory, wal));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,10_000, fetched));
    assertEquals(10_000, rowValue(fetched));
    assertEquals(5, table.rootPageId());
    assertEquals(BTreePage.MAX_ENTRIES + 1, table.rowCount());
    close(table, wal, directory);
  }

  @Test
  void recoversCompactInsertBeforePageFlush(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, 77_031);
    row.position(0);
    row.limit(Long.BYTES);
    assertEquals(
        StatusCode.OK,
        table.insert(2, 0, 77, row, new HeapInsertResult()));
    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    table = openTable(openStore(directory, wal));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,77, fetched));
    assertEquals(77_031, rowValue(fetched));
    close(table, wal, directory);
  }

  @Test
  void appendsAndRecoversRowsAcrossHeapPages(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    HeapInsertResult inserted = new HeapInsertResult();
    ByteBuffer row = ByteBuffer.allocateDirect(256);
    int rows = 130;
    for (int index = 0; index < rows; index++) {
      row.clear();
      row.putLong(0, index * 10L);
      row.position(0);
      row.limit(row.capacity());
      assertEquals(StatusCode.OK, table.insert(index + 2L, 0, index, row, inserted));
      assertEquals(index + 1, inserted.rowId());
    }
    assertEquals(rows, table.rowCount());
    assertEquals(true, table.pageCount() >= 5);
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,0, fetched));
    assertEquals(0, rowValue(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,64, fetched));
    assertEquals(640, rowValue(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,129, fetched));
    assertEquals(1_290, rowValue(fetched));

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    table = openTable(openStore(directory, wal));
    assertEquals(rows, table.rowCount());
    assertEquals(StatusCode.OK, table.fetchByKey( 0,0, fetched));
    assertEquals(0, rowValue(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,64, fetched));
    assertEquals(640, rowValue(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,129, fetched));
    assertEquals(1_290, rowValue(fetched));
    close(table, wal, directory);
  }

  @Test
  void crossesFormer65536RowCeilingThroughRealInsertIndexScanAndRecovery(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    int rows = 65_537;
    int batchSize = 64;
    long[] keys = new long[batchSize];
    int[] spaces = new int[batchSize];
    int[] rowLengths = new int[batchSize];
    ByteBuffer values = ByteBuffer.allocateDirect(batchSize * Long.BYTES);
    IndexedCommitResult committed = new IndexedCommitResult();
    long transactionId = 2;
    for (int first = 0; first < rows; first += batchSize) {
      int count = Math.min(batchSize, rows - first);
      values.clear();
      for (int index = 0; index < count; index++) {
        long key = first + index;
        keys[index] = key;
        spaces[index] = 0;
        rowLengths[index] = Long.BYTES;
        values.putLong(index * Long.BYTES, key);
      }
      values.position(0);
      values.limit(count * Long.BYTES);
      assertEquals(
          StatusCode.OK,
          table.commitInserts(
              transactionId++, spaces, keys, values, Long.BYTES, rowLengths, count, committed),
          "first=" + first + " pageCount=" + table.pageCount());
    }

    assertEquals(rows, table.rowCount());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 65_536, fetched));
    assertEquals(65_536, rowValue(fetched));

    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult result = new IndexedScanResult();
    assertEquals(StatusCode.OK, table.beginScan(table.visibleCommitSequence(), 0, 0, 0, rows, cursor));
    int scanned = 0;
    while (table.nextScan(cursor, result).isOk()) {
      scanned++;
    }
    assertEquals(rows, scanned);
    assertEquals(StatusCode.OK, table.closeScan(cursor));
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    table = openTable(openStore(directory, wal));
    assertEquals(rows, table.rowCount());
    assertEquals(StatusCode.OK, table.fetchByKey(0, 65_536, fetched));
    assertEquals(65_536, rowValue(fetched));
    close(table, wal, directory);
  }

  @Test
  void snapshotHidesRowsCommittedAfterItsBoundary(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    long beforeInsert = table.visibleCommitSequence();
    long commitSequence = table.nextCommitSequence();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, 991);
    row.position(0);
    row.limit(Long.BYTES);
    assertEquals(
        StatusCode.OK,
        table.insertCommitted(17, commitSequence, 0, 99, row, new HeapInsertResult()));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(beforeInsert, 0, 99, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(commitSequence, 0, 99, fetched));
    assertEquals(991, rowValue(fetched));
    assertEquals(StatusCode.OK, table.flush());
    close(table, wal, directory);
  }

  @Test
  void reportsRegularAndSplitCopyAmplification(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    HeapInsertResult inserted = new HeapInsertResult();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    long stagedBefore = table.stagedCopyBytes();
    long walBefore = table.walCopyBytes();
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      row.putLong(0, index);
      row.position(0);
      row.limit(Long.BYTES);
      assertEquals(StatusCode.OK, table.insert(index + 2L, 0, index, row, inserted));
    }
    assertEquals(0, table.stagedCopyBytes() - stagedBefore);
    assertEquals(
        (long) BTreePage.MAX_ENTRIES * Long.BYTES,
        table.walCopyBytes() - walBefore);

    stagedBefore = table.stagedCopyBytes();
    walBefore = table.walCopyBytes();
    row.putLong(0, 20_000);
    row.position(0);
    row.limit(Long.BYTES);
    assertEquals(StatusCode.OK, table.insert(20_002, 0, 20_000, row, inserted));
    assertEquals(3L * PageCodec.PAGE_BYTES, table.stagedCopyBytes() - stagedBefore);
    assertEquals(5L * PageCodec.PAGE_BYTES, table.walCopyBytes() - walBefore);
    assertEquals(StatusCode.OK, table.flush());
    close(table, wal, directory);
  }

  @Test
  void maintainsModelAcrossMultipleSplitsAndRecovery(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    HeapInsertResult inserted = new HeapInsertResult();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    int entries = 800;
    for (int index = 0; index < entries; index++) {
      long key = index * 641L % 809;
      row.putLong(0, key);
      row.position(0);
      row.limit(Long.BYTES);
      assertEquals(StatusCode.OK, table.insert(index + 2L, 0, key, row, inserted));
    }
    assertEquals(entries, table.rowCount());
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    table = openTable(openStore(directory, wal));
    HeapRowResult fetched = new HeapRowResult();
    for (int index = 0; index < entries; index++) {
      long key = index * 641L % 809;
      assertEquals(StatusCode.OK, table.fetchByKey( 0,key, fetched));
      assertEquals(key, rowValue(fetched));
    }
    close(table, wal, directory);
  }

  @Test
  void growsToThreeLevelsCheckpointsAndRecovers(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    int batchCapacity = 64;
    int entries = BTreePage.MAX_ENTRIES * (BTreePage.MAX_ENTRIES / 2 + 1) + 1;
    long[] keys = new long[batchCapacity];
    int[] rowLengths = new int[batchCapacity];
    ByteBuffer rows = ByteBuffer.allocateDirect(batchCapacity * Long.BYTES);
    IndexedCommitResult committed = new IndexedCommitResult();
    long transactionId = 2;
    for (int first = 0; first < entries; first += batchCapacity) {
      int count = Math.min(batchCapacity, entries - first);
      rows.clear();
      for (int index = 0; index < count; index++) {
        long key = first + index;
        keys[index] = key;
        rowLengths[index] = Long.BYTES;
        rows.putLong(index * Long.BYTES, key * 10);
      }
      rows.position(0);
      rows.limit(count * Long.BYTES);
      assertEquals(
          StatusCode.OK,
          table.commitInserts(
              transactionId++, new int[keys.length],
              keys,
              rows,
              Long.BYTES,
              rowLengths,
              count,
              committed));
    }
    assertEquals(entries, table.rowCount());
    assertEquals(3, table.treeHeight());
    assertLargeTree(table, entries);

    assertEquals(StatusCode.OK, table.flush());
    int suffix = batchCapacity;
    rows.clear();
    for (int index = 0; index < suffix; index++) {
      long key = entries + index;
      keys[index] = key;
      rowLengths[index] = Long.BYTES;
      rows.putLong(index * Long.BYTES, key * 10);
    }
    rows.position(0);
    rows.limit(suffix * Long.BYTES);
    assertEquals(
        StatusCode.OK,
        table.commitInserts(
            transactionId, new int[keys.length],
            keys,
            rows,
            Long.BYTES,
            rowLengths,
            suffix,
            committed));
    int recoveredEntries = entries + suffix;
    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    table = openTable(openStore(directory, wal));
    assertEquals(recoveredEntries, table.rowCount());
    assertEquals(3, table.treeHeight());
    assertLargeTree(table, recoveredEntries);
    close(table, wal, directory);
  }

  private static void assertLargeTree(IndexedTable table, int entries) {
    HeapRowResult fetched = new HeapRowResult();
    int[] samples = {0, 127, 128, 255, 256, entries / 2, entries - 1};
    for (int sample : samples) {
      assertEquals(StatusCode.OK, table.fetchByKey( 0,sample, fetched));
      assertEquals(sample * 10L, rowValue(fetched));
    }
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult row = new IndexedScanResult();
    assertEquals(
        StatusCode.OK,
        table.beginScan(table.visibleCommitSequence(), 0, 0, 0, entries, cursor));
    for (int expected = 0; expected < entries; expected++) {
      assertEquals(StatusCode.OK, table.nextScan(cursor, row));
      assertEquals(expected, row.key());
    }
    assertEquals(StatusCode.CONFLICT, table.nextScan(cursor, row));
    assertEquals(StatusCode.OK, table.closeScan(cursor));
  }

  private static void assertAllRows(IndexedTable table, int entries) {
    HeapRowResult fetched = new HeapRowResult();
    for (int index = 0; index < entries; index++) {
      assertEquals(StatusCode.OK, table.fetchByKey( 0,index * 10L, fetched));
      assertEquals(index * 10L, rowValue(fetched));
    }
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,-99, fetched));
  }

  private static long rowValue(HeapRowResult row) {
    ByteBuffer value = ByteBuffer.allocate(row.length());
    assertEquals(StatusCode.OK, row.copyTo(value));
    return value.getLong(0);
  }

  private static int littleInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset))
        | Byte.toUnsignedInt(source.get(offset + 1)) << 8
        | Byte.toUnsignedInt(source.get(offset + 2)) << 16
        | Byte.toUnsignedInt(source.get(offset + 3)) << 24;
  }

  private static LocalWalReadResult lastWalRecord(LocalWal wal) {
    long offset = 64;
    LocalWalReadResult result = new LocalWalReadResult();
    while (offset < wal.tailEnd()) {
      assertEquals(StatusCode.OK, wal.read(offset, result));
      offset = result.nextOffset();
    }
    return result;
  }

  private static void corruptRootPage(NioDurableDirectory directory, int rootPageId) {
    DirectoryOperationResult operation = new DirectoryOperationResult();
    assertEquals(
        StatusCode.OK,
        directory.reopen(IndexedTableStore.FILE_NAME, operation));
    DurableFile file = operation.file();
    long offset = (long) (rootPageId - 1) * PageCodec.PAGE_BYTES + 10;
    ByteBuffer oneByte = ByteBuffer.allocate(1);
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, file.read(offset, oneByte, io));
    oneByte.flip();
    oneByte.put(0, (byte) (oneByte.get(0) ^ 0x5a));
    oneByte.position(0);
    assertEquals(StatusCode.OK, file.write(offset, oneByte, io));
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, file.close());
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

  private static IndexedTableStore createStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(directory, wal, DATABASE, GENERATION, result));
    return result.store();
  }

  private static IndexedTableStore openStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, result));
    return result.store();
  }

  private static IndexedTable createTable(IndexedTableStore store) {
    IndexedTableOpenResult result = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(store, result));
    return result.table();
  }

  private static IndexedTable openTable(IndexedTableStore store) {
    IndexedTableOpenResult result = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(store, result));
    return result.table();
  }

  private static void close(
      IndexedTable table,
      LocalWal wal,
      NioDurableDirectory directory) {
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }
}
