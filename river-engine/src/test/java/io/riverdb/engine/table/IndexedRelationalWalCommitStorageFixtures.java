package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databasePlan;
import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static io.riverdb.engine.TestDatabaseResources.runtimeRoot;
import static io.riverdb.tx.TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.storage.btree.BTreeFreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.TupleBTree;
import io.riverdb.storage.btree.TupleBTreeInsertPreflightResult;
import io.riverdb.storage.btree.TupleBTreePageReference;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.TupleBTreeTreeWorkspace;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalForceTarget;
import io.riverdb.wal.local.LocalWalGroupAppendResult;
import io.riverdb.wal.local.LocalWalLogicalStream;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.wal.local.LocalWalReadResult;
import io.riverdb.wal.local.LocalWalRecordBatch;
import io.riverdb.wal.local.LocalWalReservation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.riverdb.engine.table.IndexedRelationalWalCommitFixtures.*;

/** Storage and encoding operations for relational WAL scenarios. */
final class IndexedRelationalWalCommitStorageFixtures {
  static void assertRecoveredRegistry(
      IndexedTableStore store, long keyId, int rootPageId,
      long ownerObjectId, long generation, long schemaId) {
    HeapRowResult row = new HeapRowResult();
    requireOk(store.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row));
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    requireOk(row.copyTo(bytes));
    bytes.flip();
    TupleIndexRootRecord record = new TupleIndexRootRecord();
    requireOk(TupleIndexRootRecordCodec.decode(bytes, 0, record, new CRC32C()));
    check(record.state() == TupleIndexRootRecordCodec.STATE_READY
        && record.rootPageId() == rootPageId && record.generation() == generation
        && record.ownerObjectId() == ownerObjectId
        && record.schemaId() == schemaId
        && record.privateOwner() == 0, "registry replay mismatch");
  }

  static NioDurableDirectory openDirectory(Path root) {
    return openDirectory(root, new NioIoCounters());
  }

  static NioDurableDirectory openDirectory(Path root, NioIoCounters counters) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    requireOk(NioDurableDirectory.openExisting(
        root, new FatalStateFence(), counters, 8, result));
    return result.directory();
  }

  static void prepareHybrid(
      IndexedTransactionSession session, int[] descriptor,
      long baseSpace, int key, long value) {
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    requireOk(session.insert(baseSpace, key, scalarRow(value)));
    ByteBuffer tuple = physicalFixedTuple(key, value);
    requireOk(session.preflightTupleMutations(1, 1, tuple.remaining()));
    requireOk(session.protectTupleKeyForWrite(1_000, tuple, tuple.position(), tuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), key,
        tuple, tuple.position(), tuple.remaining()));
  }

  static StatusCode coordinatedCommit(
      IndexedTransactionSession session, TransactionOutcome outcome,
      CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await();
    return session.commit(outcome);
  }

  static void assertTuple(
      IndexedTableStore store, int[] descriptor, long value, long logicalRowId) {
    ByteBuffer key = genericFixedTuple(value);
    IndexedTupleProbeResult probe = new IndexedTupleProbeResult();
    requireOk(store.probeTuplePrefixAt(
        store.currentCommitSequence(), OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
        shape(descriptor), key, 0, key.remaining(), probe));
    check(probe.found() && probe.logicalRowId() == logicalRowId,
        "grouped hybrid tuple missing for row " + logicalRowId);
  }

  static void assertBaseRow(
      IndexedTableStore store, long space, long key, long expectedValue) {
    HeapRowResult row = new HeapRowResult();
    requireOk(store.fetchByKey(space, key, row));
    check(row.getLong(0) == expectedValue, "hybrid base row value mismatch for " + key);
  }

  static LocalWal openWal(NioDurableDirectory directory, boolean existing) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    requireOk(LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  static void crashWal(LocalWal wal) throws Exception {
    Field file = LocalWal.class.getDeclaredField("file");
    file.setAccessible(true);
    requireOk(((io.riverdb.platform.file.DurableFile) file.get(wal)).close());
  }

  static long descriptorHash(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    requireOk(TupleShape.create(descriptors, result));
    return result.value().descriptorHash();
  }

  static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    requireOk(TupleShape.create(descriptors, result));
    return result.value();
  }

  static IndexedPageSet pageSet(IndexedTableStore store) throws Exception {
    Field field = IndexedTableStore.class.getDeclaredField("pages");
    field.setAccessible(true);
    return (IndexedPageSet) field.get(store);
  }

  static IndexedSessionContext context(
      TransactionManager manager,
      IndexedTable table,
      IndexedGroupCommitCoordinator coordinator,
      IndexedVacuum vacuum) {
    IndexedSessionContext.Result result = new IndexedSessionContext.Result();
    requireOk(IndexedSessionContext.bind(manager, table, coordinator, vacuum, result));
    return result.context();
  }

  static IndexedTransactionSession session(
      IndexedSessionContext context, int maximumRowBytes) {
    IndexedTransactionSessionOpenResult result =
        new IndexedTransactionSessionOpenResult();
    requireOk(context.openSession(maximumRowBytes, result));
    return result.session();
  }

  static void requireOk(StatusCode status) {
    if (!status.isOk()) throw new AssertionError("expected OK, got " + status);
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
