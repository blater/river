package io.riverdb.engine.table;


import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.*;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.TupleBTree;
import io.riverdb.storage.btree.TupleBTreeInsertPreflightResult;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.TupleBTreeTreeWorkspace;
import java.nio.ByteBuffer;


/** Concrete mutation fixtures for relational WAL tests. */
final class IndexedRelationalWalMutationFixtures {
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
}
