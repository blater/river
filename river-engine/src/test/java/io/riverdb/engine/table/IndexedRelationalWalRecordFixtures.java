package io.riverdb.engine.table;


import static io.riverdb.engine.table.IndexedRelationalWalMutationFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.*;
import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalReadResult;
import io.riverdb.wal.local.LocalWalRecordBatch;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;


/** Concrete record fixtures for relational WAL tests. */
final class IndexedRelationalWalRecordFixtures {
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
    int applications;
    int mutations;
    long commitSequence;

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
