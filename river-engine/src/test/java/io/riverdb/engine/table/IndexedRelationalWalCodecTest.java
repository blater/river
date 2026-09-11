package io.riverdb.engine.table;

import static io.riverdb.engine.table.IndexedRelationalWalMutationFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalRecordFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.*;
import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/** Tests for relational WAL codec scenarios. */
final class IndexedRelationalWalCodecTest {
  private static volatile long allocationGuard;

  @Test
  void primaryPlusSixtyFourUpdateRoundTripsBelow430KiB() {
    int[] descriptors = {
        SqlTypeDescriptor.varchar(255),
        SqlTypeDescriptor.varchar(255),
        SqlTypeDescriptor.varchar(250)
    };
    long hash = descriptorHash(descriptors);
    ByteBuffer oldTuple = physicalTuple(descriptors, 9, 'a');
    ByteBuffer newTuple = physicalTuple(descriptors, 9, 'b');
    int tupleBytes = oldTuple.remaining();
    int variableBytes = 8_192 + 130 * tupleBytes;
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(131, 65, 65 * descriptors.length);
    requireOk(source.reserve(131, 65, 65 * descriptors.length, variableBytes));
    for (int index = 0; index < 65; index++) {
      requireOk(source.appendDescriptor(
          OWNER_OBJECT_ID, 1_000 + index, 1_000 + index, hash,
          descriptors, 0, descriptors.length));
    }
    requireOk(source.appendSuboperation(
        OWNER_OBJECT_ID, -1, 0, 1,
        0, 0, SCALAR_ROOT, SCALAR_ROOT, NEXT_PAGE, NEXT_PAGE, 0, 0,
        8, 9,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
    for (int index = 0; index < 65; index++) {
      requireOk(source.appendSuboperation(
          OWNER_OBJECT_ID, index, 1 + index * 2, 2,
          index + 1, index + 10, SCALAR_ROOT, SCALAR_ROOT,
          NEXT_PAGE, NEXT_PAGE, 1, 2, 9 + index, 10 + index,
          IndexedRelationalSuboperations.REGISTRY_READY,
          IndexedRelationalSuboperations.REGISTRY_READY, 0, 0));
    }
    ByteBuffer row = ByteBuffer.allocate(8_192);
    requireOk(source.appendBase(
        0, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_UPDATE, 9, 8,
        row, 0, row.remaining()));
    for (int index = 0; index < 65; index++) {
      requireOk(source.appendTuple(
          index + 1, OWNER_OBJECT_ID,
          IndexedRelationalMutationBuffer.TUPLE_DELETE, index, 9,
          oldTuple, oldTuple.position(), oldTuple.remaining()));
      requireOk(source.appendTuple(
          index + 1, OWNER_OBJECT_ID,
          IndexedRelationalMutationBuffer.TUPLE_INSERT, index, 9,
          newTuple, newTuple.position(), newTuple.remaining()));
    }
    requireOk(source.seal());
    check(source.mutationCount() == 131, "wide update mutation count");
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(TRANSACTION_ID, OPERATION_ID, source));
    check(plan.chunkCount() == 1, "wide update should be one WAL record");
    check(plan.payloadBytesAt(0) < 430 * 1_024, "wide update exceeds 430 KiB proof bound");

    ByteBuffer encoded = ByteBuffer.allocate(plan.payloadBytesAt(0));
    requireOk(IndexedRelationalWalCodec.encode(plan, 0, encoded));
    encoded.flip();
    IndexedRelationalMutationBuffer decoded =
        new IndexedRelationalMutationBuffer(
            131, 65, 65 * io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS);
    IndexedRelationalWalDecoder decoder = new IndexedRelationalWalDecoder(decoded);
    requireOk(decoder.decode(encoded, TRANSACTION_ID, 1));
    check(decoder.complete(), "wide update decoder did not publish");
    check(decoded.mutationCount() == 131 && decoded.descriptorCount() == 65,
        "wide update round-trip counts");
    check(decoded.suboperationCount() == 66
        && decoded.ownerObjectIdAt(0) == OWNER_OBJECT_ID
        && decoded.expectedTupleRootAt(1) == 1
        && decoded.resultingTupleRootAt(1) == 10,
        "wide update recovery evidence");
    check(decoded.operationAt(1) == IndexedRelationalMutationBuffer.TUPLE_DELETE,
        "old physical entry must be retained/reused");
  }

  @Test
  void warmedTupleSessionReconfigurationAllocatesNoBytes() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean allocations = (ThreadMXBean) standard;
    allocations.setThreadAllocatedMemoryEnabled(true);
    TupleShape.Result first = new TupleShape.Result();
    TupleShape.Result second = new TupleShape.Result();
    requireOk(TupleShape.create(new int[] {SqlTypeDescriptor.BIGINT}, first));
    requireOk(TupleShape.create(new int[] {SqlTypeDescriptor.varchar(16)}, second));
    IndexedRelationalTupleSession session = new IndexedRelationalTupleSession(null);
    for (int index = 0; index < 10_000; index++) {
      allocationGuard += session.configure(1_000, 1_000, 4, first.value()).ordinal();
      allocationGuard += session.configure(1_001, 1_001, 9, second.value()).ordinal();
    }
    long thread = Thread.currentThread().threadId();
    long before = allocations.getThreadAllocatedBytes(thread);
    for (int index = 0; index < 10_000; index++) {
      allocationGuard += session.configure(1_000, 1_000, 4, first.value()).ordinal();
      allocationGuard += session.rootPageId();
      allocationGuard += session.configure(1_001, 1_001, 9, second.value()).ordinal();
      allocationGuard += session.rootPageId();
    }
    check(allocations.getThreadAllocatedBytes(thread) - before == 0,
        "warmed tuple-session reconfiguration allocated");
    check(session.rootPageId() == 9, "tuple-session root identity bled across descriptors");
  }

  @Test
  void warmedAlternatingDescriptorDecodeAllocatesNoBytesWithoutShapeBleed() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean allocations = (ThreadMXBean) standard;
    allocations.setThreadAllocatedMemoryEnabled(true);
    int[] firstDescriptor = {SqlTypeDescriptor.BIGINT};
    int[] secondDescriptor = {SqlTypeDescriptor.varchar(16)};
    ByteBuffer first = encode(descriptorOnlyPlan(firstDescriptor, OPERATION_ID + 201), 0);
    ByteBuffer second = encode(descriptorOnlyPlan(secondDescriptor, OPERATION_ID + 202), 0);
    IndexedRelationalMutationBuffer decoded = new IndexedRelationalMutationBuffer(
        0, 1, io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS);
    IndexedRelationalWalDecoder decoder = new IndexedRelationalWalDecoder(decoded);
    for (int index = 0; index < 10_000; index++) {
      requireOk(decoder.decode(first, TRANSACTION_ID, 1));
      decoder.reset();
      requireOk(decoder.decode(second, TRANSACTION_ID, 1));
      decoder.reset();
    }
    long thread = Thread.currentThread().threadId();
    long allocated = Long.MAX_VALUE;
    for (int attempt = 0; attempt < 4 && allocated != 0; attempt++) {
      long before = allocations.getThreadAllocatedBytes(thread);
      for (int index = 0; index < 10_000; index++) {
        allocationGuard += decoder.decode(first, TRANSACTION_ID, 1).ordinal();
        decoder.reset();
        allocationGuard += decoder.decode(second, TRANSACTION_ID, 1).ordinal();
        decoder.reset();
      }
      allocated = allocations.getThreadAllocatedBytes(thread) - before;
    }
    check(allocated == 0, "warmed relational descriptor decode allocated " + allocated);
    requireOk(decoder.decode(second, TRANSACTION_ID, 1));
    check(decoded.shapeAt(0).matchesDescriptors(secondDescriptor, 0, 1),
        "alternating descriptor decode retained stale shape");
  }

  @Test
  void descriptorShapeCacheRetentionStaysWithinBoundedAccounting() {
    int capacity = 128;
    IndexedRelationalMutationDescriptors descriptors =
        new IndexedRelationalMutationDescriptors(capacity, capacity);
    IndexedRelationalMutationDescriptors addressable =
        new IndexedRelationalMutationDescriptors(
            IndexedRelationalMutationBuffer.MAX_INDEX_DESCRIPTORS,
            IndexedRelationalMutationBuffer.MAX_INDEX_DESCRIPTORS);
    check(addressable.accountedBytes()
            < descriptors.accountedBytesForReservation(capacity, capacity),
        "unused addressable capacity exceeded a small populated reservation");
    addressable.release();
    requireOk(descriptors.reserve(capacity, capacity));
    long before = descriptors.accountedBytes();

    for (int index = 0; index < capacity; index++) {
      int[] shape = {SqlTypeDescriptor.varchar(index + 1)};
      check(descriptors.append(
          OWNER_OBJECT_ID,
          1_000 + index,
          KEY_SCHEMA_ID,
          descriptorHash(shape),
          shape,
          0,
          1).isOk(), "descriptor shape append failed");
    }

    check(descriptors.count() == capacity, "descriptor shapes were not retained");
    check(descriptors.accountedBytes() == before,
        "descriptor shape cache grew beyond its bounded retained accounting");
  }

  @Test
  void registryReaderPreservesPostLookupCopyStatus(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(
        directory, wal, DATABASE, GENERATION, databaseProviderLease(2), created));
    IndexedTableOpenResult opened = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), opened));
    ByteBuffer malformed = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES + 1);
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), opened.table().nextTransactionId(), 2);
    IndexedVacuum vacuum = new IndexedVacuum(manager, opened.table());
    IndexedSessionContext context = context(manager, opened.table(), null, vacuum);
    IndexedTransactionSession session = session(context, malformed.remaining());
    TransactionOutcome outcome = new TransactionOutcome();
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    requireOk(session.insert(CatalogKeyspace.INDEX_ROOT_SPACE, 1_000, malformed));
    requireOk(session.commit(outcome));
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    IndexedRelationalMutation mutation = liveRootMutation(
        descriptor, descriptorHash(descriptor), 4, 4, 1, 2, 1, 2,
        IndexedRelationalMutation.REGISTRY_READY,
        IndexedRelationalMutation.REGISTRY_READY, 0, 0, 4, 4);
    check(commitRelationalQuiescent(created.store(),
        TRANSACTION_ID + 103, mutation, new IndexedCommitResult())
        == StatusCode.INVALID_EXTERNAL_INPUT,
        "registry row copy status collapsed to corruption");
    created.store().closeOpenFile();
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void worstMutationCountChunksWithinPhysicalRecordLimit() {
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(384, 0, 0);
    int rowBytes = 8_192;
    requireOk(source.reserve(384, 0, 0, 384 * rowBytes));
    ByteBuffer row = ByteBuffer.allocate(rowBytes);
    appendBaseSuboperations(source, 384);
    for (int index = 0; index < 384; index++) {
      requireOk(source.appendBase(
          index, OWNER_OBJECT_ID,
          IndexedRelationalMutationBuffer.BASE_INSERT, index + 1L, 0,
          row, 0, rowBytes));
    }
    requireOk(source.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(TRANSACTION_ID, OPERATION_ID + 1, source));
    check(plan.chunkCount() > 1, "worst mutation group did not exercise chunking");
    for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
      check(plan.payloadBytesAt(chunk) <= WalRecordCodec.MAX_PAYLOAD_BYTES,
          "chunk exceeds LocalWal payload");
    }

    IndexedRelationalMutationBuffer decoded =
        new IndexedRelationalMutationBuffer(384, 0, 0);
    IndexedRelationalWalDecoder decoder = new IndexedRelationalWalDecoder(decoded);
    for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
      ByteBuffer encoded = encode(plan, chunk);
      requireOk(decoder.decode(
          encoded, TRANSACTION_ID, chunk == plan.chunkCount() - 1 ? 1 : 0));
      check(decoded.mutationCount() == (decoder.complete() ? 384 : 0),
          "partial group became visible");
    }
    check(decoder.complete(), "worst mutation group did not complete");
  }

  @Test
  void rejectsTruncationReorderingAndDigestCorruption() {
    IndexedRelationalMutationBuffer one =
        new IndexedRelationalMutationBuffer(1, 0, 0);
    ByteBuffer row = ByteBuffer.allocate(32);
    requireOk(one.reserve(1, 0, 0, row.remaining()));
    appendBaseSuboperations(one, 1);
    requireOk(one.appendBase(
        0, OWNER_OBJECT_ID,
        IndexedRelationalMutationBuffer.BASE_INSERT, 1, 0, row, 0, row.remaining()));
    requireOk(one.seal());
    IndexedRelationalWalPlan onePlan = new IndexedRelationalWalPlan();
    requireOk(onePlan.plan(TRANSACTION_ID, OPERATION_ID + 2, one));

    ByteBuffer truncated = encode(onePlan, 0);
    truncated.limit(truncated.limit() - 1);
    check(decodeOne(truncated) == StatusCode.CORRUPTION, "truncation accepted");

    ByteBuffer corrupted = encode(onePlan, 0);
    int last = corrupted.limit() - 1;
    corrupted.put(last, (byte) (corrupted.get(last) ^ 1));
    check(decodeOne(corrupted) == StatusCode.CORRUPTION, "digest corruption accepted");

    IndexedRelationalMutationBuffer many =
        new IndexedRelationalMutationBuffer(384, 0, 0);
    ByteBuffer largeRow = ByteBuffer.allocate(8_192);
    requireOk(many.reserve(384, 0, 0, 384 * largeRow.remaining()));
    appendBaseSuboperations(many, 384);
    for (int index = 0; index < 384; index++) {
      requireOk(many.appendBase(
          index, OWNER_OBJECT_ID,
          IndexedRelationalMutationBuffer.BASE_INSERT, index + 1L, 0,
          largeRow, 0, largeRow.remaining()));
    }
    requireOk(many.seal());
    IndexedRelationalWalPlan manyPlan = new IndexedRelationalWalPlan();
    requireOk(manyPlan.plan(TRANSACTION_ID, OPERATION_ID + 3, many));
    ByteBuffer second = encode(manyPlan, 1);
    IndexedRelationalMutationBuffer output =
        new IndexedRelationalMutationBuffer(384, 0, 0);
    IndexedRelationalWalDecoder decoder = new IndexedRelationalWalDecoder(output);
    check(decoder.decode(second, TRANSACTION_ID, 0) == StatusCode.CORRUPTION,
        "reordered chunk accepted");
    check(output.mutationCount() == 0, "corrupt group became visible");
  }

  @Test
  void rootOnlyIndexCreationRoundTrips() {
    int[] descriptors = {SqlTypeDescriptor.BIGINT};
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(0, 1, 1);
    requireOk(source.reserve(0, 1, 1, 0));
    requireOk(source.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, 1_000, descriptorHash(descriptors),
        descriptors, 0, 1));
    requireOk(source.appendSuboperation(
        OWNER_OBJECT_ID, 0, 0, 0,
        0, 7, SCALAR_ROOT, SCALAR_ROOT,
        NEXT_PAGE, NEXT_PAGE + 1, 0, 1, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_BUILDING, 0, TRANSACTION_ID));
    requireOk(source.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(TRANSACTION_ID, OPERATION_ID + 4, source));

    IndexedRelationalMutationBuffer decoded =
        new IndexedRelationalMutationBuffer(
            0, 1, io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS);
    IndexedRelationalWalDecoder decoder = new IndexedRelationalWalDecoder(decoded);
    requireOk(decoder.decode(encode(plan, 0), TRANSACTION_ID, 1));
    check(decoder.complete() && decoded.mutationCount() == 0
        && decoded.suboperationCount() == 1
        && decoded.descriptorOwnerObjectIdAt(0) == OWNER_OBJECT_ID
        && decoded.resultingGenerationAt(0) == 1
        && decoded.resultingRegistryStateAt(0)
            == IndexedRelationalSuboperations.REGISTRY_BUILDING
        && decoded.resultingPrivateOwnerAt(0) == TRANSACTION_ID,
        "root-only lifecycle did not round-trip");
  }

  @Test
  void rejectsUnchainedGlobalKeyHeapAndRegistryTransitions() {
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(2, 1, 1);
    requireOk(source.reserve(2, 1, 1, 16));
    requireOk(source.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, 1_000, descriptorHash(descriptor), descriptor, 0, 1));
    requireOk(source.appendSuboperation(
        OWNER_OBJECT_ID, -1, 0, 1,
        0, 0, SCALAR_ROOT, SCALAR_ROOT + 1, NEXT_PAGE, NEXT_PAGE + 1, 0, 0,
        0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
    check(source.appendSuboperation(
        OWNER_OBJECT_ID, -1, 1, 1,
        0, 0, SCALAR_ROOT, SCALAR_ROOT, NEXT_PAGE + 1, NEXT_PAGE + 1, 0, 0,
        1, 2,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0)
        == StatusCode.INVALID_EXTERNAL_INPUT, "unchained scalar root accepted");

    IndexedRelationalMutationBuffer keys =
        new IndexedRelationalMutationBuffer(0, 1, 1);
    requireOk(keys.reserve(0, 1, 1, 0));
    requireOk(keys.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, 1_000, descriptorHash(descriptor), descriptor, 0, 1));
    requireOk(keys.appendSuboperation(
        OWNER_OBJECT_ID, 0, 0, 0,
        5, 6, SCALAR_ROOT, SCALAR_ROOT, NEXT_PAGE, NEXT_PAGE, 4, 5, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_BUILDING,
        IndexedRelationalSuboperations.REGISTRY_BUILDING,
        TRANSACTION_ID, TRANSACTION_ID));
    check(keys.appendSuboperation(
        OWNER_OBJECT_ID, 0, 0, 0,
        5, 7, SCALAR_ROOT, SCALAR_ROOT, NEXT_PAGE, NEXT_PAGE, 5, 6, 1, 2,
        IndexedRelationalSuboperations.REGISTRY_BUILDING,
        IndexedRelationalSuboperations.REGISTRY_READY, TRANSACTION_ID, 0)
        == StatusCode.INVALID_EXTERNAL_INPUT, "unchained tuple root accepted");
  }

  static StatusCode decodeOne(ByteBuffer source) {
    IndexedRelationalMutationBuffer output =
        new IndexedRelationalMutationBuffer(1, 0, 0);
    return new IndexedRelationalWalDecoder(output).decode(source, TRANSACTION_ID, 1);
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
}
