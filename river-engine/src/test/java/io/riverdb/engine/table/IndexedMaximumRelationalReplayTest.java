package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
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
  void replaysMaximumRowsPlusPrimaryAndSecondaryRegistryVersions(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.create(directory, wal, DATABASE, GENERATION, created));
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
        IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, recovered));
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
    return new IndexedTransactionSession(
        manager, table, 128, null, null, 384, 4_096, null);
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

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, result));
    return result.directory();
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
