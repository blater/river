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

/** Concrete fixtures for the relational WAL Codec scenarios. */
final class IndexedRelationalWalCodecFixtures {
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

  static StatusCode decodeOne(ByteBuffer source) {
    IndexedRelationalMutationBuffer output =
        new IndexedRelationalMutationBuffer(1, 0, 0);
    return new IndexedRelationalWalDecoder(output).decode(source, TRANSACTION_ID, 1);
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

  static ByteBuffer encode(IndexedRelationalWalPlan plan, int chunk) {
    ByteBuffer result = ByteBuffer.allocate(plan.payloadBytesAt(chunk));
    requireOk(IndexedRelationalWalCodec.encode(plan, chunk, result));
    result.flip();
    return result;
  }

  static IndexedRelationalWalPlan descriptorOnlyPlan(
      int[] descriptor, long operationId) {
    IndexedRelationalMutationBuffer mutations =
        new IndexedRelationalMutationBuffer(0, 1, descriptor.length);
    requireOk(mutations.reserve(0, 1, descriptor.length, 0));
    requireOk(mutations.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, 1_000, descriptorHash(descriptor),
        descriptor, 0, descriptor.length));
    requireOk(mutations.appendSuboperation(
        OWNER_OBJECT_ID, 0, 0, 0, 4, 4, SCALAR_ROOT, SCALAR_ROOT,
        NEXT_PAGE, NEXT_PAGE, 1, 2, 1, 2,
        IndexedRelationalSuboperations.REGISTRY_READY,
        IndexedRelationalSuboperations.REGISTRY_READY, 0, 0));
    requireOk(mutations.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(TRANSACTION_ID, operationId, mutations));
    return plan;
  }

  static StatusCode commitRelationalQuiescent(
      IndexedTableStore store,
      long transactionId,
      IndexedRelationalMutation mutation,
      IndexedCommitResult result) {
    return store.commitRelational(
        transactionId, mutation, Long.MAX_VALUE, result);
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

  static LocalWal openWal(NioDurableDirectory directory, boolean existing) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    requireOk(LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  static void appendBaseSuboperations(
      IndexedRelationalMutationBuffer mutations, int count) {
    requireOk(mutations.appendLogicalRowFloor(OWNER_OBJECT_ID, count + 1L));
    for (int index = 0; index < count; index++) {
      requireOk(mutations.appendSuboperation(
          OWNER_OBJECT_ID, -1, index, 1,
          0, 0, SCALAR_ROOT, SCALAR_ROOT,
          NEXT_PAGE, NEXT_PAGE, 0, 0, index, index + 1L,
          IndexedRelationalSuboperations.REGISTRY_ABSENT,
          IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
    }
  }

  static long descriptorHash(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    requireOk(TupleShape.create(descriptors, result));
    return result.value().descriptorHash();
  }

  static ByteBuffer physicalTuple(int[] descriptors, long logicalRowId, char value) {
    ByteBuffer result = ByteBuffer.allocate(3_080);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    requireOk(builder.beginIndex(result, 0, descriptors.length));
    requireOk(builder.addText(descriptors[0], repeated(value, 255)));
    requireOk(builder.addText(descriptors[1], repeated(value, 255)));
    requireOk(builder.addText(descriptors[2], repeated(value, 250)));
    requireOk(builder.finishPhysical(logicalRowId));
    result.limit(builder.keyBytes());
    result.position(0);
    return result;
  }

  static String repeated(char value, int count) {
    char[] characters = new char[count];
    for (int index = 0; index < count; index++) characters[index] = value;
    return new String(characters);
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
