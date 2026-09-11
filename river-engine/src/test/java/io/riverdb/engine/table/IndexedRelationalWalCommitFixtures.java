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

import static io.riverdb.engine.table.IndexedRelationalWalCommitStorageFixtures.*;

/** Concrete fixtures for the relational WAL scenarios. */
final class IndexedRelationalWalCommitFixtures {
  static volatile long allocationGuard;
  static final long TRANSACTION_ID = 41;
  static final long OPERATION_ID = 73;
  static final long OWNER_OBJECT_ID = 19;
  static final long SECOND_OWNER_OBJECT_ID = 20;
  static final long KEY_SCHEMA_ID = 2_000;
  static final int SCALAR_ROOT = 3;
  static final int NEXT_PAGE = 4;
  static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(919, 929);
  static final WalGeneration GENERATION = WalGeneration.of(1);

  static IndexedRelationalMutation liveBaseMutation(int expectedRoot, long value) {
    IndexedRelationalMutation mutation = new IndexedRelationalMutation(1, 0, 0);
    requireOk(mutation.reserve(1, 0, 0, Long.BYTES));
    requireOk(mutation.appendLogicalRowFloor(OWNER_OBJECT_ID, 2));
    requireOk(mutation.appendSuboperation(
        OWNER_OBJECT_ID, -1, 0, 1, 0, 0, expectedRoot, SCALAR_ROOT,
        NEXT_PAGE, NEXT_PAGE, 0, 0, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    row.putLong(0, value);
    requireOk(mutation.appendBase(
        0, OWNER_OBJECT_ID, IndexedRelationalMutation.BASE_INSERT,
        1, 0, row, 0, Long.BYTES));
    requireOk(mutation.seal());
    return mutation;
  }

  static int tupleInsertNewPageCount(
      IndexedTableStore store, int[] descriptor, ByteBuffer tuple) throws Exception {
    TupleIndexRootRecord record = registryRecord(store, 1_000);
    check(record.state() == TupleIndexRootRecordCodec.STATE_READY
            && record.rootPageId() > 0,
        "tuple split probe requires a READY rooted index");
    IndexedTupleRootState root = new IndexedTupleRootState(
        record.keyId(), record.schemaId(), record.rootPageId());
    IndexedTuplePageProvider provider = new IndexedTuplePageProvider(pageSet(store), root);
    TupleBTree tree = new TupleBTree(provider, record.schemaId(), shape(descriptor));
    int height = BTreeStructuralLimits.MAXIMUM_LEVELS;
    TupleBTreeTreeWorkspace workspace = new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[height], new int[height], new int[height]);
    TupleBTreeInsertPreflightResult result = new TupleBTreeInsertPreflightResult();
    requireOk(provider.begin(0));
    StatusCode status = tree.preflightInsert(
        tuple, tuple.position(), tuple.remaining(), workspace, result);
    StatusCode finished = provider.finish(status);
    provider.cancelRoot();
    requireOk(finished);
    check(!result.keyExists(), "tuple split probe reused an existing key");
    return result.newPageCount();
  }

  static int tuplePageCount(IndexedTableStore store, long keyId) throws Exception {
    IndexedPageSet pages = pageSet(store);
    int count = 0;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (pages.isPresent(pageId)
          && pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
          && pages.ownerKeyId(pageId) == keyId) {
        count++;
      }
    }
    return count;
  }

  static IndexedRelationalMutation liveRootMutation(
      int[] descriptor, long hash,
      int expectedRoot, int resultingRoot,
      long expectedGeneration, long resultingGeneration,
      long expectedHeap, long resultingHeap,
      int expectedState, int resultingState,
      long expectedOwner, long resultingOwner,
      int expectedNext, int resultingNext) {
    return liveRootMutation(
        descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
        expectedRoot, resultingRoot, expectedGeneration, resultingGeneration,
        expectedHeap, resultingHeap, expectedState, resultingState,
        expectedOwner, resultingOwner, expectedNext, resultingNext);
  }

  static IndexedRelationalMutation liveRootMutation(
      int[] descriptor, long hash, long owner, long keyId,
      int expectedRoot, int resultingRoot,
      long expectedGeneration, long resultingGeneration,
      long expectedHeap, long resultingHeap,
      int expectedState, int resultingState,
      long expectedOwner, long resultingOwner,
      int expectedNext, int resultingNext) {
    return liveRootMutation(
        descriptor, hash, owner, keyId, keyId,
        expectedRoot, resultingRoot, expectedGeneration, resultingGeneration,
        expectedHeap, resultingHeap, expectedState, resultingState,
        expectedOwner, resultingOwner, expectedNext, resultingNext);
  }

  static IndexedRelationalMutation liveRootMutation(
      int[] descriptor, long hash, long owner, long keyId, long schemaId,
      int expectedRoot, int resultingRoot,
      long expectedGeneration, long resultingGeneration,
      long expectedHeap, long resultingHeap,
      int expectedState, int resultingState,
      long expectedOwner, long resultingOwner,
      int expectedNext, int resultingNext) {
    int expectedCursor = expectedState == IndexedRelationalMutation.REGISTRY_DROPPING
        && expectedRoot == 0
            ? resultingState == IndexedRelationalMutation.REGISTRY_ABSENT
                ? expectedNext : BTreeRootPage.FIRST_REUSABLE_PAGE_ID
            : 0;
    int resultingCursor = resultingState == IndexedRelationalMutation.REGISTRY_DROPPING
        && resultingRoot == 0 ? expectedState == IndexedRelationalMutation.REGISTRY_DROPPING
            && expectedRoot == 0 ? resultingNext : BTreeRootPage.FIRST_REUSABLE_PAGE_ID : 0;
    return liveRootMutation(
        descriptor, hash, owner, keyId, schemaId,
        expectedRoot, resultingRoot, expectedGeneration, resultingGeneration,
        expectedHeap, resultingHeap, expectedState, resultingState,
        expectedOwner, resultingOwner, expectedNext, resultingNext,
        expectedCursor, resultingCursor);
  }

  static IndexedRelationalMutation liveRootMutation(
      int[] descriptor, long hash, long owner, long keyId, long schemaId,
      int expectedRoot, int resultingRoot,
      long expectedGeneration, long resultingGeneration,
      long expectedHeap, long resultingHeap,
      int expectedState, int resultingState,
      long expectedOwner, long resultingOwner,
      int expectedNext, int resultingNext,
      int expectedCleanupCursor, int resultingCleanupCursor) {
    IndexedRelationalMutation mutation =
        new IndexedRelationalMutation(0, 1, descriptor.length);
    requireOk(mutation.reserve(0, 1, descriptor.length, 0));
    requireOk(mutation.appendDescriptor(
        owner, keyId, schemaId, hash, descriptor, 0, descriptor.length));
    requireOk(mutation.appendSuboperation(
        owner, 0, 0, 0, expectedRoot, resultingRoot, 3, 3,
        expectedNext, resultingNext, expectedGeneration, resultingGeneration,
        expectedHeap, resultingHeap, expectedState, resultingState,
        expectedOwner, resultingOwner, expectedCleanupCursor, resultingCleanupCursor));
    requireOk(mutation.seal());
    return mutation;
  }

  static StatusCode commitRelationalQuiescent(
      IndexedTableStore store,
      long transactionId,
      IndexedRelationalMutation mutation,
      IndexedCommitResult result) {
    return store.commitRelational(
        transactionId, mutation, Long.MAX_VALUE, result);
  }

  static ByteBuffer physicalFixedTuple(long logicalRowId, long value) {
    ByteBuffer result = ByteBuffer.allocate(32);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    requireOk(builder.beginIndex(result, 0, 1));
    requireOk(builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    requireOk(builder.finishPhysical(logicalRowId));
    result.position(0);
    result.limit(builder.keyBytes());
    return result;
  }

  static ByteBuffer genericFixedTuple(long value) {
    ByteBuffer result = ByteBuffer.allocate(24);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    requireOk(builder.beginTuple(result, 0, 1));
    requireOk(builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    requireOk(builder.finishTuple());
    result.position(0);
    result.limit(builder.keyBytes());
    return result;
  }

  static ByteBuffer scalarRow(long value) {
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    row.putLong(0, value);
    return row;
  }

  static TupleIndexRootRecord registryRecord(
      IndexedTableStore store, long keyId) {
    HeapRowResult row = new HeapRowResult();
    requireOk(store.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row));
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    requireOk(row.copyTo(bytes));
    bytes.flip();
    TupleIndexRootRecord record = new TupleIndexRootRecord();
    requireOk(TupleIndexRootRecordCodec.decode(bytes, 0, record, new CRC32C()));
    return record;
  }

  static void assertReadyRegistry(
      IndexedTableStore store, long keyId, long owner, int rootPageId,
      long generation) {
    TupleIndexRootRecord record = registryRecord(store, keyId);
    check(record.state() == TupleIndexRootRecordCodec.STATE_READY
        && record.rootPageId() == rootPageId && record.ownerObjectId() == owner
        && record.schemaId() == KEY_SCHEMA_ID && record.generation() == generation
        && record.privateOwner() == 0 && record.cleanupCursor() == 0,
        "batched READY registry mismatch");
  }

  static void assertAbsentRegistry(
      IndexedTableStore store, long keyId, long owner, long generation) {
    TupleIndexRootRecord record = registryRecord(store, keyId);
    check(record.state() == TupleIndexRootRecordCodec.STATE_ABSENT
        && record.rootPageId() == 0 && record.ownerObjectId() == owner
        && record.schemaId() == KEY_SCHEMA_ID && record.generation() == generation
        && record.privateOwner() == 0 && record.cleanupCursor() == 0,
        "batched ABSENT registry mismatch");
  }

  static void assertRecoveredRegistry(IndexedTableStore store) {
    assertRecoveredRegistry(store, 1_000, 4, OWNER_OBJECT_ID);
  }

  static void assertRecoveredRegistry(
      IndexedTableStore store, long keyId, int rootPageId, long ownerObjectId) {
    assertRecoveredRegistry(store, keyId, rootPageId, ownerObjectId, 3);
  }

  static void assertRecoveredRegistry(
      IndexedTableStore store, long keyId, int rootPageId,
      long ownerObjectId, long generation) {
    assertRecoveredRegistry(
        store, keyId, rootPageId, ownerObjectId, generation, keyId);
  }
}
