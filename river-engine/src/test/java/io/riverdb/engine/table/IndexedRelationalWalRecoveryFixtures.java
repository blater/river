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

import static io.riverdb.engine.table.IndexedRelationalWalRecoveryStorageFixtures.*;

/** Concrete fixtures for the relational WAL scenarios. */
final class IndexedRelationalWalRecoveryFixtures {
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

  static IndexedRelationalMutation liveTupleInsertMutation(
      int[] descriptor, long hash, int expectedRoot, int resultingRoot,
      int expectedNext, int resultingNext, long expectedGeneration, long expectedHeap,
      long firstLogicalRowId, int tupleCount, char value) {
    IndexedRelationalMutation mutation =
        new IndexedRelationalMutation(tupleCount, 1, descriptor.length);
    requireOk(mutation.reserve(
        tupleCount, 1, descriptor.length, tupleCount * 3_080));
    requireOk(mutation.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, 1_000, hash, descriptor, 0, descriptor.length));
    requireOk(mutation.appendSuboperation(
        OWNER_OBJECT_ID, 0, 0, tupleCount, expectedRoot, resultingRoot,
        SCALAR_ROOT, SCALAR_ROOT, expectedNext, resultingNext,
        expectedGeneration, expectedGeneration + 1, expectedHeap, expectedHeap + 1,
        IndexedRelationalMutation.REGISTRY_BUILDING,
        IndexedRelationalMutation.REGISTRY_BUILDING, 2, 2));
    for (int index = 0; index < tupleCount; index++) {
      long logicalRowId = firstLogicalRowId + index;
      ByteBuffer tuple = physicalTuple(descriptor, logicalRowId, value);
      requireOk(mutation.appendTuple(
          0, OWNER_OBJECT_ID, IndexedRelationalMutation.TUPLE_INSERT,
          0, logicalRowId, tuple, 0, tuple.remaining()));
    }
    requireOk(mutation.seal());
    return mutation;
  }

  static long predictTupleInsert(
      IndexedTableStore store, int[] descriptor, int expectedRoot,
      long firstLogicalRowId, int tupleCount, char value) throws Exception {
    IndexedPageSet pages = pageSet(store);
    pages.resetChanges();
    IndexedRelationalTupleSession session = new IndexedRelationalTupleSession(pages);
    StatusCode status = session.configure(1_000, 1_000, expectedRoot, shape(descriptor));
    try {
      if (status.isOk() && expectedRoot == 0) status = session.initialize();
      for (int index = 0; status.isOk() && index < tupleCount; index++) {
        status = session.insert(physicalTuple(
            descriptor, firstLogicalRowId + index, value));
      }
      if (status.isOk()) status = session.validate();
      requireOk(status);
      ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
      requireOk(BTreeRootPage.validate(metadata));
      return (long) session.rootPageId() << 32
          | Integer.toUnsignedLong(BTreeRootPage.nextPageId(metadata));
    } finally {
      pages.clearStagedFlags();
      pages.resetChanges();
    }
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

  static IndexedRelationalWalPlan oneBasePlan(
      long transactionId, long operationId, long value) {
    IndexedRelationalMutationBuffer mutations =
        new IndexedRelationalMutationBuffer(1, 0, 0);
    requireOk(mutations.reserve(1, 0, 0, Long.BYTES));
    requireOk(mutations.appendLogicalRowFloor(OWNER_OBJECT_ID, 2));
    requireOk(mutations.appendSuboperation(
        OWNER_OBJECT_ID, -1, 0, 1, 0, 0, SCALAR_ROOT, SCALAR_ROOT,
        NEXT_PAGE, NEXT_PAGE, 0, 0, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    row.putLong(0, value);
    requireOk(mutations.appendBase(
        0, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_INSERT,
        1, 0, row, 0, Long.BYTES));
    requireOk(mutations.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(transactionId, operationId, mutations));
    return plan;
  }

  static LocalWalReadResult record(
      IndexedRelationalWalPlan plan, int chunk,
      long commitSequence, int decision, long offset) {
    ByteBuffer payload = encode(plan, chunk);
    LocalWalReadResult result = new LocalWalReadResult();
    result.header().set(
        payload.remaining(), payload.remaining(),
        IndexedRelationalWalCodec.WAL_FORMAT_ID,
        IndexedRelationalWalCodec.WAL_FORMAT_VERSION,
        chunk + 1L, TRANSACTION_ID, commitSequence, decision);
    result.set(offset + payload.remaining(), offset + payload.remaining(), payload);
    return result;
  }

  static void appendRootGroup(
      LocalWal wal, int[] descriptor, long hash, long sequence,
      int expectedRoot, int resultingRoot, long expectedGeneration,
      long resultingGeneration, int expectedState, int resultingState,
      long expectedOwner, long resultingOwner, int expectedNext, int resultingNext) {
    IndexedRelationalMutationBuffer mutations =
        new IndexedRelationalMutationBuffer(0, 1, descriptor.length);
    requireOk(mutations.reserve(0, 1, descriptor.length, 0));
    requireOk(mutations.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, 1_000, hash, descriptor, 0, descriptor.length));
    requireOk(mutations.appendSuboperation(
        OWNER_OBJECT_ID, 0, 0, 0, expectedRoot, resultingRoot,
        SCALAR_ROOT, SCALAR_ROOT, expectedNext, resultingNext,
        expectedGeneration, resultingGeneration, expectedGeneration, resultingGeneration,
        expectedState, resultingState, expectedOwner, resultingOwner));
    commitGroup(wal, mutations, sequence);
  }

  static void appendRootGroup(
      LocalWal wal, int[] descriptor, long hash, long owner, long keyId, long schemaId,
      long sequence, int expectedRoot, int resultingRoot, long expectedGeneration,
      long resultingGeneration, int expectedState, int resultingState,
      long expectedOwner, long resultingOwner, int expectedNext, int resultingNext,
      long expectedHeap) {
    int expectedCursor = expectedState == IndexedRelationalSuboperations.REGISTRY_DROPPING
        && expectedRoot == 0 ? BTreeRootPage.FIRST_REUSABLE_PAGE_ID : 0;
    int resultingCursor = resultingState == IndexedRelationalSuboperations.REGISTRY_DROPPING
        && resultingRoot == 0 ? expectedState == IndexedRelationalSuboperations.REGISTRY_DROPPING
            && expectedRoot == 0 ? resultingNext : BTreeRootPage.FIRST_REUSABLE_PAGE_ID : 0;
    appendRootGroup(
        wal, descriptor, hash, owner, keyId, schemaId, sequence,
        expectedRoot, resultingRoot, expectedGeneration, resultingGeneration,
        expectedState, resultingState, expectedOwner, resultingOwner,
        expectedNext, resultingNext, expectedHeap, expectedCursor, resultingCursor);
  }

  static void appendRootGroup(
      LocalWal wal, int[] descriptor, long hash, long owner, long keyId, long schemaId,
      long sequence, int expectedRoot, int resultingRoot, long expectedGeneration,
      long resultingGeneration, int expectedState, int resultingState,
      long expectedOwner, long resultingOwner, int expectedNext, int resultingNext,
      long expectedHeap, int expectedCleanupCursor, int resultingCleanupCursor) {
    IndexedRelationalMutationBuffer mutations =
        new IndexedRelationalMutationBuffer(0, 1, descriptor.length);
    requireOk(mutations.reserve(0, 1, descriptor.length, 0));
    requireOk(mutations.appendDescriptor(
        owner, keyId, schemaId, hash, descriptor, 0, descriptor.length));
    requireOk(mutations.appendSuboperation(
        owner, 0, 0, 0, expectedRoot, resultingRoot,
        SCALAR_ROOT, SCALAR_ROOT, expectedNext, resultingNext,
        expectedGeneration, resultingGeneration, expectedHeap, expectedHeap + 1,
        expectedState, resultingState, expectedOwner, resultingOwner,
        expectedCleanupCursor, resultingCleanupCursor));
    commitGroup(wal, mutations, sequence);
  }

  static void appendTupleInsertGroup(LocalWal wal, int[] descriptor, long hash) {
    ByteBuffer tuple = physicalFixedTuple(1, 771);
    IndexedRelationalMutationBuffer mutations =
        new IndexedRelationalMutationBuffer(1, 1, 1);
    requireOk(mutations.reserve(1, 1, 1, tuple.remaining()));
    requireOk(mutations.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, 1_000, hash, descriptor, 0, 1));
    requireOk(mutations.appendSuboperation(
        OWNER_OBJECT_ID, 0, 0, 1, 4, 4, SCALAR_ROOT, SCALAR_ROOT, 5, 5,
        2, 3, 2, 3, IndexedRelationalSuboperations.REGISTRY_READY,
        IndexedRelationalSuboperations.REGISTRY_READY, 0, 0));
    requireOk(mutations.appendTuple(
        0, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.TUPLE_INSERT,
        0, 1, tuple, 0, tuple.remaining()));
    commitGroup(wal, mutations, 4);
  }

  static void appendTupleInsertGroup(
      LocalWal wal, int[] descriptor, long hash, long owner,
      long keyId, long schemaId, int root, long sequence,
      long resultingGeneration, long expectedHeap, ByteBuffer tuple) {
    IndexedRelationalMutationBuffer mutations =
        new IndexedRelationalMutationBuffer(1, 1, descriptor.length);
    requireOk(mutations.reserve(1, 1, descriptor.length, tuple.remaining()));
    requireOk(mutations.appendDescriptor(
        owner, keyId, schemaId, hash, descriptor, 0, descriptor.length));
    requireOk(mutations.appendSuboperation(
        owner, 0, 0, 1, root, root, SCALAR_ROOT, SCALAR_ROOT, 6, 6,
        resultingGeneration - 1, resultingGeneration, expectedHeap, expectedHeap + 1,
        IndexedRelationalSuboperations.REGISTRY_READY,
        IndexedRelationalSuboperations.REGISTRY_READY, 0, 0));
    requireOk(mutations.appendTuple(
        0, owner, IndexedRelationalMutationBuffer.TUPLE_INSERT,
        0, 2, tuple, tuple.position(), tuple.remaining()));
    commitGroup(wal, mutations, sequence);
  }

  static void appendBaseInsertGroup(LocalWal wal, long sequence, long expectedHeap) {
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    row.putLong(0, 771);
    IndexedRelationalMutationBuffer mutations =
        new IndexedRelationalMutationBuffer(1, 0, 0);
    requireOk(mutations.reserve(1, 0, 0, Long.BYTES));
    requireOk(mutations.appendLogicalRowFloor(OWNER_OBJECT_ID, 2));
    requireOk(mutations.appendSuboperation(
        OWNER_OBJECT_ID, -1, 0, 1, 0, 0, SCALAR_ROOT, SCALAR_ROOT, 6, 6,
        0, 0, expectedHeap, expectedHeap + 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
    requireOk(mutations.appendBase(
        0, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_INSERT,
        1, 0, row, 0, Long.BYTES));
    commitGroup(wal, mutations, sequence);
  }

  static void appendIncompleteBaseGroup(LocalWal wal) {
    ByteBuffer row = ByteBuffer.allocate(8_192);
    IndexedRelationalMutationBuffer mutations =
        new IndexedRelationalMutationBuffer(384, 0, 0);
    requireOk(mutations.reserve(384, 0, 0, 384 * row.remaining()));
    requireOk(mutations.appendLogicalRowFloor(OWNER_OBJECT_ID, 386));
    for (int index = 0; index < 384; index++) {
      requireOk(mutations.appendSuboperation(
          OWNER_OBJECT_ID, -1, index, 1, 0, 0,
          SCALAR_ROOT, SCALAR_ROOT, 6, 6, 0, 0, 3 + index, 4 + index,
          IndexedRelationalSuboperations.REGISTRY_ABSENT,
          IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
      requireOk(mutations.appendBase(
          index, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_INSERT,
          index + 2L, 0, row, 0, row.remaining()));
    }
    requireOk(mutations.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(91, OPERATION_ID + 91, mutations));
    check(plan.chunkCount() > 1, "incomplete EOF group did not chunk");
    ByteBuffer payload = encode(plan, 0);
    LocalWalReservation reservation = new LocalWalReservation();
    requireOk(wal.reserve(payload.remaining(), reservation));
    reservation.writablePayload().put(payload);
    requireOk(wal.publish(
        reservation, 91, 0, 0,
        IndexedRelationalWalCodec.WAL_FORMAT_ID,
        IndexedRelationalWalCodec.WAL_FORMAT_VERSION,
        new LocalWalAppendResult()));
  }

  static void commitGroup(
      LocalWal wal, IndexedRelationalMutationBuffer mutations, long sequence) {
    requireOk(mutations.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(sequence, OPERATION_ID + sequence, mutations));
    IndexedRelationalWalCommitter committer =
        new IndexedRelationalWalCommitter(wal, new IndexedGroupCommitMetrics());
    requireOk(committer.appendAndForce(plan, sequence));
    requireOk(committer.releaseForced());
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

  static ByteBuffer physicalTextTuple(long logicalRowId, String value) {
    ByteBuffer result = ByteBuffer.allocate(64);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    requireOk(builder.beginIndex(result, 0, 1));
    requireOk(builder.addText(SqlTypeDescriptor.varchar(16), value));
    requireOk(builder.finishPhysical(logicalRowId));
    result.position(0);
    result.limit(builder.keyBytes());
    return result;
  }

  static ByteBuffer scalarRow(long value) {
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    row.putLong(0, value);
    return row;
  }

  static final class PrefixBatch implements LocalWalRecordBatch {
    private final LocalWalRecordBatch source;
    private final int records;

    PrefixBatch(LocalWalRecordBatch recordSource, int recordCount) {
      source = recordSource;
      records = recordCount;
    }

    @Override
    public int recordCount() {
      return records;
    }

    @Override
    public int payloadBytes(int record) {
      return source.payloadBytes(record);
    }

    @Override
    public StatusCode encodePayload(int record, ByteBuffer target) {
      return source.encodePayload(record, target);
    }
  }

  static final class RecordingReplay implements IndexedRelationalWalReplay {
    private int applications;
    private int mutations;
    private long commitSequence;

    @Override
    public StatusCode apply(
        IndexedRelationalMutationBuffer value,
        long recordStart,
        long recordEnd,
        long committedAt,
        long oldestVisibleCommitSequence,
        boolean recovery) {
      check(value.sealed() && recordStart > 0 && recordEnd > recordStart,
          "replay received incomplete group");
      applications++;
      mutations = value.mutationCount();
      commitSequence = committedAt;
      return StatusCode.OK;
    }
  }
}
