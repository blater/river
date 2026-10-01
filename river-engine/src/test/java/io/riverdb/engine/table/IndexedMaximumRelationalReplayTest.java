package io.riverdb.engine.table;

import static io.riverdb.engine.TestDurableStorage.openDirectory;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.LockMemoryEnvelope;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Crash replay regression for row versions plus one registry version per maintained index. */
final class IndexedMaximumRelationalReplayTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(1_026, 1_024);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final long OWNER = 4;
  private static final long PRIMARY_KEY = 1_000;
  private static final long SECONDARY_KEY = 1_001;
  private static final long SCHEMA = 1_000;
  private static final int ROWS = 1_024;

  @Test
  void broadScanMergesScalarAndSparseBaseSpaces(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK, IndexedTableStore.create(
        directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult opened = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(created.store(), opened));
    IndexedTable table = opened.table();
    IndexedTransactionSession session = session(table);
    long first = CatalogKeyspace.relationalBaseRowSpace(1);
    long last = CatalogKeyspace.relationalBaseRowSpace(
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID);
    long[] spaces = {
        0, first, first + 1, last, CatalogKeyspace.FIRST_INDEX_SPACE};
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    TransactionOutcome outcome = new TransactionOutcome();
    for (int index = 0; index < spaces.length; index++) {
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      row.putLong(0, index + 1);
      assertEquals(StatusCode.OK, session.insert(spaces[index], 1, row));
      assertEquals(StatusCode.OK, session.commit(outcome));
    }

    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult result = new IndexedScanResult();
    assertEquals(StatusCode.OK, table.beginScan(
        table.currentCommitSequence(), 0, Long.MIN_VALUE,
        OrderedKey.INFINITY_SPACE, 0, cursor));
    for (int index = 0; index < spaces.length; index++) {
      assertEquals(StatusCode.OK, table.nextScan(cursor, result));
      assertEquals(spaces[index], result.keySpace());
      assertEquals(index + 1, result.row().getLong(0));
    }
    assertEquals(StatusCode.CONFLICT, table.nextScan(cursor, result));
    assertEquals(StatusCode.OK, table.closeScan(cursor));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, table.beginScan(
        table.currentCommitSequence(), first, Long.MIN_VALUE,
        last + 1, Long.MIN_VALUE, cursor));
    for (int index = 1; index <= 3; index++) {
      assertEquals(StatusCode.OK, table.nextScan(cursor, result));
      assertEquals(spaces[index], result.keySpace());
    }
    assertEquals(StatusCode.CONFLICT, table.nextScan(cursor, result));
    assertEquals(StatusCode.OK, table.closeScan(cursor));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, table.beginScan(
        table.currentCommitSequence(), first, Long.MIN_VALUE,
        first + 1, 2, cursor));
    for (int index = 1; index <= 2; index++) {
      assertEquals(StatusCode.OK, table.nextScan(cursor, result));
      assertEquals(spaces[index], result.keySpace());
    }
    assertEquals(StatusCode.CONFLICT, table.nextScan(cursor, result));
    assertEquals(StatusCode.OK, table.closeScan(cursor));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void sparseHighTableAndRowIdsKeepSnapshotAndScanOrder(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.create(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult opened = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(created.store(), opened));
    IndexedTransactionSession session = session(opened.table());
    long space = CatalogKeyspace.relationalBaseRowSpace(
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID);
    long high = 10_000_000;
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    TransactionOutcome outcome = new TransactionOutcome();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    row.putLong(0, 1);
    assertEquals(StatusCode.OK, session.insert(space, 1, row));
    assertEquals(StatusCode.OK, session.commit(outcome));
    long oldSnapshot = created.store().currentCommitSequence();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    row.putLong(0, 10);
    assertEquals(StatusCode.OK, session.insert(space, high, row));
    assertEquals(StatusCode.OK, session.commit(outcome));
    long beforeUpdate = created.store().currentCommitSequence();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    row.putLong(0, 20);
    assertEquals(StatusCode.OK, session.update(space, high, row));
    assertEquals(StatusCode.OK, session.commit(outcome));

    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK,
        created.store().fetchByKeyAt(oldSnapshot, space, 1, fetched));
    assertEquals(1, fetched.getLong(0));
    assertEquals(StatusCode.CONFLICT,
        created.store().fetchByKeyAt(oldSnapshot, space, high, fetched));
    assertEquals(StatusCode.OK,
        created.store().fetchByKeyAt(beforeUpdate, space, high, fetched));
    assertEquals(10, fetched.getLong(0));
    assertHeadScan(opened.table(), created.store(), space, high);
    IndexedPageSet pages = IndexedRelationalWalStorageFixtures.pageSet(created.store());
    IndexedLogicalHeadDirectory heads = new IndexedLogicalHeadDirectory(null, pages);
    IndexedHeadLookupResult head = new IndexedHeadLookupResult();
    assertEquals(StatusCode.OK, heads.lookup(
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID, high, head));
    java.lang.reflect.Field kernelField = IndexedTableStore.class.getDeclaredField("kernel");
    kernelField.setAccessible(true);
    IndexedTableKernel kernel = (IndexedTableKernel) kernelField.get(created.store());
    ByteBuffer vacuumEntry = ByteBuffer.allocate(
        IndexedWalCodec.VACUUM_ENTRY_BYTES + Long.BYTES);
    IndexedWalCodec.encodeVacuumEntry(
        vacuumEntry, 0, space, high, head.rowId(), Long.BYTES, false);
    vacuumEntry.putLong(IndexedWalCodec.VACUUM_ENTRY_BYTES, 20);
    assertEquals(StatusCode.OK, kernel.beginVacuumApply());
    assertEquals(StatusCode.OK, kernel.applyVacuumEntry(vacuumEntry, 0, 1));
    assertEquals(StatusCode.CORRUPTION, kernel.applyVacuumEntry(vacuumEntry, 0, 2));
    kernel.resetVacuumApply();
    assertHeadScan(opened.table(), created.store(), space, high);
    assertEquals(StatusCode.OK, opened.table().vacuum(
        opened.table().nextTransactionId(), new IndexedVacuumResult()));
    assertHeadScan(opened.table(), created.store(), space, high);
    assertEquals(StatusCode.OK, created.store().validate());
    assertEquals(StatusCode.OK, created.store().flush());
    assertEquals(StatusCode.OK, created.store().close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult recovered = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.openExisting(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(4), recovered));
    IndexedTableOpenResult reopened = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(recovered.store(), reopened));
    assertHeadScan(reopened.table(), recovered.store(), space, high);
    assertEquals(StatusCode.OK, recovered.store().close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void assertHeadScan(
      IndexedTable table, IndexedTableStore store, long space, long high) {
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult result = new IndexedScanResult();
    assertEquals(StatusCode.OK, table.beginScan(
        store.currentCommitSequence(), space, Long.MIN_VALUE,
        space + 1, Long.MIN_VALUE, cursor));
    assertEquals(StatusCode.OK, table.nextScan(cursor, result));
    assertEquals(1, result.key());
    assertEquals(1, result.row().getLong(0));
    assertEquals(StatusCode.OK, table.nextScan(cursor, result));
    assertEquals(high, result.key());
    assertEquals(20, result.row().getLong(0));
    assertEquals(StatusCode.CONFLICT, table.nextScan(cursor, result));
    assertEquals(StatusCode.OK, table.closeScan(cursor));
  }

  @Test
  void replaysMaximumRowsPlusPrimaryAndSecondaryRegistryVersions(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.create(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult opened = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(created.store(), opened));
    IndexedTransactionSession session = session(opened.table());
    TupleShape shape = shape();
    createIndexes(session, shape);
    assertEquals(StatusCode.OK, created.store().flush());

    commitRows(session, shape);
    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult recovered = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.openExisting(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(4), recovered));
    assertBaseValue(recovered.store(), 1, 2);
    assertBaseValue(recovered.store(), ROWS, ROWS * 2L);
    assertIndexedValue(recovered.store(), PRIMARY_KEY, ROWS, ROWS, shape);
    assertIndexedValue(recovered.store(), SECONDARY_KEY, ROWS * 2L, ROWS, shape);
    assertEquals(StatusCode.OK, recovered.store().flush());
    assertEquals(StatusCode.OK, recovered.store().close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static void createIndexes(IndexedTransactionSession session, TupleShape shape) {
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.preflightTupleIndexLifecycles(2));
    assertEquals(StatusCode.OK,
        session.stageTupleIndexBuilding(OWNER, PRIMARY_KEY, SCHEMA, OWNER, shape));
    assertEquals(StatusCode.OK,
        session.stageTupleIndexBuilding(OWNER, SECONDARY_KEY, SCHEMA, OWNER, shape));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.preflightTupleIndexLifecycles(2));
    assertEquals(StatusCode.OK,
        session.stageTupleIndexReady(OWNER, PRIMARY_KEY, SCHEMA, OWNER, shape));
    assertEquals(StatusCode.OK,
        session.stageTupleIndexReady(OWNER, SECONDARY_KEY, SCHEMA, OWNER, shape));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
  }

  private static void commitRows(IndexedTransactionSession session, TupleShape shape) {
    ByteBuffer primary = ByteBuffer.allocate(32);
    ByteBuffer secondary = ByteBuffer.allocate(32);
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    int tupleBytes = encode(primary, 1, 1) + encode(secondary, 1, 2);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.preflightTupleMutations(ROWS * 2, 2, ROWS * tupleBytes));
    for (int id = 1; id <= ROWS; id++) {
      row.putLong(0, id * 2L);
      assertEquals(StatusCode.OK, session.insert(baseSpace(), id, row));
      assertEquals(StatusCode.OK, append(session, PRIMARY_KEY, shape, id, id, primary));
      assertEquals(StatusCode.OK,
          append(session, SECONDARY_KEY, shape, id, id * 2L, secondary));
    }
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
  }

  private static StatusCode append(
      IndexedTransactionSession session, long keyId, TupleShape shape,
      long logicalRowId, long value, ByteBuffer key) {
    int bytes = encode(key, logicalRowId, value);
    StatusCode status = session.protectTupleKeyForWrite(
        keyId, key, 0, bytes);
    if (!status.isOk()) return status;
    return session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT, OWNER, keyId, SCHEMA,
        shape, logicalRowId, key, 0, bytes);
  }

  private static int encode(ByteBuffer target, long logicalRowId, long value) {
    target.clear();
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginIndex(target, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishPhysical(logicalRowId));
    target.position(0);
    target.limit(builder.keyBytes());
    return builder.keyBytes();
  }

  private static void assertBaseValue(IndexedTableStore store, long key, long expected) {
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, store.fetchByKey(baseSpace(), key, row));
    assertEquals(expected, row.getLong(0));
  }

  private static void assertIndexedValue(
      IndexedTableStore store, long keyId, long value,
      long logicalRowId, TupleShape shape) {
    ByteBuffer key = ByteBuffer.allocate(24);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginTuple(key, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishTuple());
    IndexedTupleProbeResult result = new IndexedTupleProbeResult();
    assertEquals(StatusCode.OK, store.probeTuplePrefixAt(
        store.currentCommitSequence(), OWNER, keyId, SCHEMA, shape,
        key, 0, builder.keyBytes(), result));
    assertTrue(result.found());
    assertEquals(logicalRowId, result.logicalRowId());
  }

  private static IndexedTransactionSession session(IndexedTable table) {
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4,
        new LockMemoryEnvelope(32L << 20));
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext.Result contextResult = new IndexedSessionContext.Result();
    assertEquals(
        StatusCode.OK,
        IndexedSessionContext.bind(manager, table, null, vacuum, contextResult));
    IndexedTransactionSessionOpenResult sessionResult =
        new IndexedTransactionSessionOpenResult();
    assertEquals(StatusCode.OK, contextResult.context().openSession(128, sessionResult));
    return sessionResult.session();
  }

  private static TupleShape shape() {
    TupleShape.Result result = new TupleShape.Result();
    assertEquals(StatusCode.OK,
        TupleShape.create(new int[] {SqlTypeDescriptor.BIGINT}, result));
    return result.value();
  }

  private static long baseSpace() {
    return CatalogKeyspace.relationalBaseRowSpace(OWNER);
  }

  private static LocalWal openWal(NioDurableDirectory directory, boolean existing) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    StatusCode status = existing
        ? LocalWal.openExisting(directory, DATABASE, GENERATION, result)
        : LocalWal.open(directory, DATABASE, GENERATION, result);
    assertEquals(StatusCode.OK, status);
    return result.wal();
  }
}
