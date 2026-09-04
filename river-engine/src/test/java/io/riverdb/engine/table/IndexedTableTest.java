package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
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
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
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
  void insertsSplitsLooksUpAndReopens(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    int entries = BTreePage.MAX_ENTRIES + 1;
    for (int index = entries - 1; index >= 0; index--) {
      row.putLong(0, index * 10L);
      row.position(0);
      row.limit(Long.BYTES);
      writer.insert(0, index * 10L, row);
    }
    assertEquals(entries, table.rowCount());
    assertEquals(5, table.pageCount());
    assertEquals(5, table.rootPageId());
    assertEquals(StatusCode.CONFLICT, writer.tryInsert(0, 100, row));
    assertEquals(entries, table.rowCount());
    assertAllRows(table, entries);

    LocalWalReadResult record = lastWalRecord(wal);
    assertEquals(writer.lastCommittedTransactionId, record.header().transactionId());
    assertEquals(entries + 1L, record.header().commitSequence());
    assertEquals(IndexedRelationalWalCodec.WAL_FORMAT_ID, record.header().formatId());
    assertEquals(IndexedRelationalWalCodec.WAL_FORMAT_VERSION, record.header().formatVersion());

    assertEquals(StatusCode.OK, writer.session.close());
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
    writer = new TransactionWriter(table, Long.BYTES);
    writer.insert(0, 99_999, row);
    HeapRowResult reopenedInsert = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,99_999, reopenedInsert));
    assertEquals(99_999, rowValue(reopenedInsert));
    assertEquals(StatusCode.OK, writer.session.close());
    close(table, wal, directory);
  }

  @Test
  void persistsAndScansFullSignedPairsAcrossSpaces(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    long[] spaces = {3, 2, 3, 2};
    long[] keys = {Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE};
    for (int index = 0; index < keys.length; index++) {
      row.putLong(0, index + 1);
      row.position(0);
      row.limit(Long.BYTES);
      assertEquals(
          StatusCode.OK,
          writer.tryInsert(spaces[index], keys[index], row));
    }
    assertEquals(StatusCode.OK, writer.session.close());
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
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      row.putLong(0, index);
      row.position(0);
      row.limit(Long.BYTES);
      writer.insert(0, index, row);
    }
    assertEquals(StatusCode.OK, table.flush());

    row.putLong(0, 10_000);
    row.position(0);
    row.limit(Long.BYTES);
    writer.insert(0, 10_000, row);
    assertEquals(StatusCode.OK, writer.session.close());
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
  void recoversLogicalInsertBeforePageFlush(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, 77_031);
    row.position(0);
    row.limit(Long.BYTES);
    writer.insert(0, 77, row);
    assertEquals(StatusCode.OK, writer.session.close());
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
    TransactionWriter writer = new TransactionWriter(table, 256);
    ByteBuffer row = ByteBuffer.allocateDirect(256);
    int rows = 130;
    for (int index = 0; index < rows; index++) {
      row.clear();
      row.putLong(0, index * 10L);
      row.position(0);
      row.limit(row.capacity());
      writer.insert(0, index, row);
      assertEquals(index + 1, table.rowCount());
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

    assertEquals(StatusCode.OK, writer.session.close());
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
    long[] spaces = new long[batchSize];
    int[] rowLengths = new int[batchSize];
    ByteBuffer values = ByteBuffer.allocateDirect(batchSize * Long.BYTES);
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
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
      assertEquals(StatusCode.OK,
          writer.insertBatch(spaces, keys, values, Long.BYTES, rowLengths, count),
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
    assertEquals(StatusCode.OK, writer.session.close());
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
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, 991);
    row.position(0);
    row.limit(Long.BYTES);
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
    writer.insert(0, 99, row);
    long commitSequence = table.visibleCommitSequence();
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(beforeInsert, 0, 99, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(commitSequence, 0, 99, fetched));
    assertEquals(991, rowValue(fetched));
    assertEquals(StatusCode.OK, writer.session.close());
    assertEquals(StatusCode.OK, table.flush());
    close(table, wal, directory);
  }

  @Test
  void reportsRegularAndSplitCopyAmplification(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    long stagedBefore = table.stagedCopyBytes();
    long walBefore = table.walCopyBytes();
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      row.putLong(0, index);
      row.position(0);
      row.limit(Long.BYTES);
      writer.insert(0, index, row);
    }
    assertEquals(
        2L * BTreePage.MAX_ENTRIES * PageCodec.PAGE_BYTES,
        table.stagedCopyBytes() - stagedBefore);
    assertEquals(
        (long) BTreePage.MAX_ENTRIES * Long.BYTES,
        table.walCopyBytes() - walBefore);

    stagedBefore = table.stagedCopyBytes();
    walBefore = table.walCopyBytes();
    row.putLong(0, 20_000);
    row.position(0);
    row.limit(Long.BYTES);
    writer.insert(0, 20_000, row);
    assertEquals(3L * PageCodec.PAGE_BYTES, table.stagedCopyBytes() - stagedBefore);
    assertEquals(Long.BYTES, table.walCopyBytes() - walBefore);
    assertEquals(StatusCode.OK, writer.session.close());
    assertEquals(StatusCode.OK, table.flush());
    close(table, wal, directory);
  }

  @Test
  void maintainsModelAcrossMultipleSplitsAndRecovery(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    int entries = 800;
    for (int index = 0; index < entries; index++) {
      long key = index * 641L % 809;
      row.putLong(0, key);
      row.position(0);
      row.limit(Long.BYTES);
      writer.insert(0, key, row);
    }
    assertEquals(entries, table.rowCount());
    assertEquals(StatusCode.OK, writer.session.close());
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
    long[] spaces = new long[batchCapacity];
    int[] rowLengths = new int[batchCapacity];
    ByteBuffer rows = ByteBuffer.allocateDirect(batchCapacity * Long.BYTES);
    TransactionWriter writer = new TransactionWriter(table, Long.BYTES);
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
          writer.insertBatch(spaces, keys, rows, Long.BYTES, rowLengths, count));
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
        writer.insertBatch(spaces, keys, rows, Long.BYTES, rowLengths, suffix));
    int recoveredEntries = entries + suffix;
    assertEquals(StatusCode.OK, writer.session.close());
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
        IndexedTableStore.create(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(4), result));
    return result.store();
  }

  private static IndexedTableStore openStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(4), result));
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

  private static final class TransactionWriter {
    private final IndexedTransactionSession session;
    private final TransactionOutcome outcome = new TransactionOutcome();
    private long lastCommittedTransactionId;

    private TransactionWriter(IndexedTable table, int maximumRowBytes) {
      TransactionManager manager = new TransactionManager(
          DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
      IndexedVacuum vacuum = new IndexedVacuum(manager, table);
      IndexedSessionContext.Result contextResult = new IndexedSessionContext.Result();
      assertEquals(
          StatusCode.OK,
          IndexedSessionContext.bind(manager, table, null, vacuum, contextResult));
      IndexedTransactionSessionOpenResult sessionResult =
          new IndexedTransactionSessionOpenResult();
      assertEquals(
          StatusCode.OK,
          contextResult.context().openSession(maximumRowBytes, sessionResult));
      session = sessionResult.session();
    }

    private void insert(long space, long key, ByteBuffer value) {
      assertEquals(StatusCode.OK, tryInsert(space, key, value));
    }

    private StatusCode tryInsert(long space, long key, ByteBuffer value) {
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      long transactionId = session.transaction().transactionId();
      StatusCode status = session.insert(space, key, value);
      if (!status.isOk()) {
        assertEquals(StatusCode.OK, session.abort(outcome));
        return status;
      }
      status = session.commit(outcome);
      if (status.isOk()) {
        lastCommittedTransactionId = transactionId;
      }
      return status;
    }

    private StatusCode insertBatch(
        long[] spaces,
        long[] keys,
        ByteBuffer rows,
        int rowStride,
        int[] rowLengths,
        int count) {
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      long transactionId = session.transaction().transactionId();
      StatusCode status = StatusCode.OK;
      for (int index = 0; index < count && status.isOk(); index++) {
        rows.limit(rows.capacity());
        rows.position(index * rowStride);
        rows.limit(index * rowStride + rowLengths[index]);
        status = session.insert(spaces[index], keys[index], rows);
      }
      if (!status.isOk()) {
        assertEquals(StatusCode.OK, session.abort(outcome));
        return status;
      }
      status = session.commit(outcome);
      if (status.isOk()) {
        lastCommittedTransactionId = transactionId;
      }
      return status;
    }
  }
}
