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
import io.riverdb.wal.local.LocalWalForceResult;
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

/** Grouped relational WAL capacity and corruption proof. */
final class IndexedRelationalWalHarnessTest {
  private static volatile long allocationGuard;
  private static final long TRANSACTION_ID = 41;
  private static final long OPERATION_ID = 73;
  private static final long OWNER_OBJECT_ID = 19;
  private static final long SECOND_OWNER_OBJECT_ID = 20;
  private static final long KEY_SCHEMA_ID = 2_000;
  private static final int SCALAR_ROOT = 3;
  private static final int NEXT_PAGE = 4;
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(919, 929);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

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
  void descriptorShapeCacheIsBoundedAroundCallerCapacity() throws Exception {
    Field cache = IndexedRelationalMutationDescriptors.class.getDeclaredField("shapeCache");
    cache.setAccessible(true);
    int[] capacities = {0, 1, IndexedRelationalMutationBuffer.MAX_INDEX_DESCRIPTORS};
    int[] expected = {0, 2, 64};
    for (int index = 0; index < capacities.length; index++) {
      int capacity = capacities[index];
      IndexedRelationalMutationDescriptors descriptors =
          new IndexedRelationalMutationDescriptors(capacity, capacity);
      check(((TupleShape[]) cache.get(descriptors)).length == expected[index],
          "descriptor shape cache ignored caller capacity");
    }
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

  @Test
  void productionDispatchRetainsContiguousGroupAndPublishesOnlyFinalChunk() {
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(384, 0, 0);
    ByteBuffer row = ByteBuffer.allocate(8_192);
    requireOk(source.reserve(384, 0, 0, 384 * row.remaining()));
    appendBaseSuboperations(source, 384);
    for (int index = 0; index < 384; index++) {
      requireOk(source.appendBase(
          index, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_INSERT,
          index + 1L, 0, row, 0, row.remaining()));
    }
    requireOk(source.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(TRANSACTION_ID, OPERATION_ID + 5, source));
    RecordingReplay replay = new RecordingReplay();
    IndexedWalRecovery recovery = new IndexedWalRecovery(
        null, null, null, null, new IndexedStorePhase(), replay);
    long offset = 1_000;
    for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
      boolean last = chunk == plan.chunkCount() - 1;
      LocalWalReadResult record = record(
          plan, chunk, last ? 91 : 0, last ? 1 : 0, offset);
      requireOk(recovery.applyOperation(offset, record, null, 90, Long.MAX_VALUE));
      check(replay.applications == (last ? 1 : 0), "partial group was published");
      offset = record.nextOffset();
    }
    check(replay.mutations == 384 && replay.commitSequence == 91,
        "complete group replay evidence");
    offset = 1_000;
    for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
      boolean last = chunk == plan.chunkCount() - 1;
      LocalWalReadResult record = record(
          plan, chunk, last ? 91 : 0, last ? 1 : 0, offset);
      requireOk(recovery.applyOperation(offset, record, null, 91, Long.MAX_VALUE));
      offset = record.nextOffset();
    }
    check(replay.applications == 1, "already-published group replayed twice");
  }

  @Test
  void productionDispatchRejectsInterleaving() {
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(384, 0, 0);
    ByteBuffer row = ByteBuffer.allocate(8_192);
    requireOk(source.reserve(384, 0, 0, 384 * row.remaining()));
    appendBaseSuboperations(source, 384);
    for (int index = 0; index < 384; index++) {
      requireOk(source.appendBase(
          index, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_INSERT,
          index + 1L, 0, row, 0, row.remaining()));
    }
    requireOk(source.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(TRANSACTION_ID, OPERATION_ID + 6, source));
    IndexedWalRecovery recovery = new IndexedWalRecovery(
        null, null, null, null, new IndexedStorePhase(), new RecordingReplay());
    LocalWalReadResult first = record(plan, 0, 0, 0, 1_000);
    requireOk(recovery.applyOperation(1_000, first, null, 90, Long.MAX_VALUE));
    LocalWalReadResult interleaved = new LocalWalReadResult();
    interleaved.header().set(
        1, 0, IndexedTableStore.WAL_FORMAT_ID, IndexedTableStore.WAL_FORMAT_VERSION,
        2, TRANSACTION_ID + 1, 91, 1);
    interleaved.set(2_000, ByteBuffer.allocate(0));
    check(recovery.applyOperation(
        first.nextOffset(), interleaved, null, 90, Long.MAX_VALUE)
        == StatusCode.CORRUPTION, "interleaved legacy record accepted");
  }

  @Test
  void streamsMoreThanOneProviderBatchAndReplaysOnlyTheFinalDecision(
      @TempDir Path root) {
    int mutations = 2_048;
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(mutations, 0, 0);
    ByteBuffer row = ByteBuffer.allocate(8_192);
    requireOk(source.reserve(mutations, 0, 0, mutations * row.remaining()));
    appendBaseSuboperations(source, mutations);
    for (int index = 0; index < mutations; index++) {
      requireOk(source.appendBase(
          index, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_INSERT,
          index + 1L, 0, row, 0, row.remaining()));
    }
    requireOk(source.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(TRANSACTION_ID, OPERATION_ID + 7, source));
    check(plan.chunkCount() > 16,
        "fixture did not cross the removed fixed-slot boundary");

    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedRelationalWalCommitter committer =
        new IndexedRelationalWalCommitter(wal, new IndexedGroupCommitMetrics());
    requireOk(committer.appendAndForce(plan, 91));
    check(committer.recordStart() > 0 && committer.recordEnd() > committer.recordStart(),
        "streamed logical extent was not retained");
    requireOk(committer.releaseForced());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    RecordingReplay replay = new RecordingReplay();
    IndexedWalRecovery recovery = new IndexedWalRecovery(
        wal, null, null, null, new IndexedStorePhase(), replay);
    long offset = io.riverdb.format.wal.WalFileHeaderCodec.HEADER_BYTES;
    LocalWalReadResult record = new LocalWalReadResult();
    while (offset < wal.tailEnd()) {
      requireOk(wal.read(offset, record));
      requireOk(recovery.applyOperation(
          offset, record, GENERATION, 90, Long.MAX_VALUE));
      offset = record.nextOffset();
    }
    check(replay.applications == 1 && replay.mutations == mutations
        && replay.commitSequence == 91,
        "streamed continuation chain did not publish exactly once");
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void recoveryTruncatesContinuationCrashBeforeANewCommitAndSecondReopen(
      @TempDir Path root) throws Exception {
    int mutations = 2_048;
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(mutations, 0, 0);
    ByteBuffer row = ByteBuffer.allocate(8_192);
    requireOk(source.reserve(mutations, 0, 0, mutations * row.remaining()));
    appendBaseSuboperations(source, mutations);
    for (int index = 0; index < mutations; index++) {
      requireOk(source.appendBase(
          index, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_INSERT,
          index + 1L, 0, row, 0, row.remaining()));
    }
    requireOk(source.seal());
    IndexedRelationalWalPlan interrupted = new IndexedRelationalWalPlan();
    requireOk(interrupted.plan(TRANSACTION_ID, OPERATION_ID + 8, source));
    check(interrupted.chunkCount() > 16,
        "fixture did not cross the removed fixed-slot boundary");

    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    LocalWalLogicalStream stream = new LocalWalLogicalStream();
    requireOk(wal.beginLogicalStream(
        interrupted.transactionId(), IndexedRelationalWalCodec.WAL_FORMAT_ID,
        IndexedRelationalWalCodec.WAL_FORMAT_VERSION, stream));
    LocalWalGroupAppendResult appended = new LocalWalGroupAppendResult();
    requireOk(wal.appendLogicalStreamContinuation(
        stream, new PrefixBatch(interrupted, interrupted.recordCount() - 1), appended));
    long interruptedStart = appended.startOffset();
    requireOk(wal.forceLogicalStreamBatch(stream, new LocalWalForceResult()));
    requireOk(wal.releaseLogicalStreamBatch(stream));
    crashWal(wal);
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    RecordingReplay firstRecovery = new RecordingReplay();
    IndexedWalRecovery recovery = new IndexedWalRecovery(
        wal, null, null, null, new IndexedStorePhase(), firstRecovery);
    requireOk(recovery.recover(GENERATION, true, 90));
    check(wal.tailEnd() == interruptedStart && wal.nextJournalSequence() == 1,
        "recovery did not rewind the decisionless suffix");
    check(firstRecovery.applications == 0,
        "incomplete continuation reached relational replay");

    IndexedRelationalWalPlan committed = oneBasePlan(
        TRANSACTION_ID + 1, OPERATION_ID + 9, 29);
    IndexedRelationalWalCommitter committer =
        new IndexedRelationalWalCommitter(wal, new IndexedGroupCommitMetrics());
    requireOk(committer.appendAndForce(committed, 91));
    requireOk(committer.releaseForced());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    RecordingReplay secondRecovery = new RecordingReplay();
    recovery = new IndexedWalRecovery(
        wal, null, null, null, new IndexedStorePhase(), secondRecovery);
    requireOk(recovery.recover(GENERATION, true, 90));
    check(secondRecovery.applications == 1 && secondRecovery.commitSequence == 91,
        "commit after suffix repair did not survive the second reopen");
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void replicatedOpenRepairsDecisionlessSuffixBeforeEnablingQuorum(
      @TempDir Path root) throws Exception {
    Path primaryPath = Files.createDirectory(root.resolve("primary"));
    Path followerOnePath = Files.createDirectory(root.resolve("follower-one"));
    Path followerTwoPath = Files.createDirectory(root.resolve("follower-two"));
    Path[] followerPaths = {followerOnePath, followerTwoPath};
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    requireOk(EmbeddedDatabase.createWithDurableWalQuorum(
        runtimeRoot(), databasePlan(8),
        primaryPath, followerPaths, 2, DATABASE, GENERATION, 8, DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        opened));
    requireOk(opened.database().close());

    NioDurableDirectory primaryDirectory = openDirectory(primaryPath);
    NioDurableDirectory followerOneDirectory = openDirectory(followerOnePath);
    NioDurableDirectory followerTwoDirectory = openDirectory(followerTwoPath);
    LocalWal primary = openWal(primaryDirectory, true);
    LocalWal followerOne = openWal(followerOneDirectory, true);
    LocalWal followerTwo = openWal(followerTwoDirectory, true);
    requireOk(primary.enableDurableQuorum(
        new LocalWal[] {followerOne, followerTwo}, 2));

    int mutations = 2_048;
    IndexedRelationalMutationBuffer source =
        new IndexedRelationalMutationBuffer(mutations, 0, 0);
    ByteBuffer row = ByteBuffer.allocate(8_192);
    requireOk(source.reserve(mutations, 0, 0, mutations * row.remaining()));
    appendBaseSuboperations(source, mutations);
    for (int index = 0; index < mutations; index++) {
      requireOk(source.appendBase(
          index, OWNER_OBJECT_ID, IndexedRelationalMutationBuffer.BASE_INSERT,
          index + 1L, 0, row, 0, row.remaining()));
    }
    requireOk(source.seal());
    IndexedRelationalWalPlan interrupted = new IndexedRelationalWalPlan();
    requireOk(interrupted.plan(TRANSACTION_ID, OPERATION_ID + 10, source));
    LocalWalLogicalStream stream = new LocalWalLogicalStream();
    requireOk(primary.beginLogicalStream(
        interrupted.transactionId(), IndexedRelationalWalCodec.WAL_FORMAT_ID,
        IndexedRelationalWalCodec.WAL_FORMAT_VERSION, stream));
    requireOk(primary.appendLogicalStreamContinuation(
        stream, new PrefixBatch(interrupted, interrupted.recordCount() - 1),
        new LocalWalGroupAppendResult()));
    requireOk(primary.forceLogicalStreamBatch(stream, new LocalWalForceResult()));
    requireOk(primary.releaseLogicalStreamBatch(stream));
    crashWal(primary);
    crashWal(followerOne);
    crashWal(followerTwo);
    requireOk(primaryDirectory.close());
    requireOk(followerOneDirectory.close());
    requireOk(followerTwoDirectory.close());

    requireOk(EmbeddedDatabase.openWithDurableWalQuorum(
        runtimeRoot(), databasePlan(8),
        primaryPath, followerPaths, 2, DATABASE, GENERATION, 8, DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        opened));
    EmbeddedDatabase database = opened.database();
    check(database.availableDurableNodeCount() == 3,
        "repaired replicas were not admitted to quorum");
    EmbeddedSessionOpenResult sessionResult = new EmbeddedSessionOpenResult();
    requireOk(database.createSession(128, sessionResult));
    IndexedTransactionSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    requireOk(session.insert(0, 811, scalarRow(8_110)));
    requireOk(session.commit(outcome));
    requireOk(database.close());

    requireOk(EmbeddedDatabase.openWithDurableWalQuorum(
        runtimeRoot(), databasePlan(8),
        primaryPath, followerPaths, 2, DATABASE, GENERATION, 8, DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        opened));
    database = opened.database();
    requireOk(database.createSession(128, sessionResult));
    session = sessionResult.session();
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    HeapRowResult fetched = new HeapRowResult();
    requireOk(session.fetchByKey(0, 811, fetched));
    check(fetched.getLong(0) == 8_110,
        "commit after replicated suffix repair did not survive second reopen");
    requireOk(session.commit(outcome));
    requireOk(database.close());
  }

  @Test
  void committedRelationalRecordRequiresPositiveSequenceBeforeCoveredSkip() {
    IndexedRelationalWalPlan plan = oneBasePlan(TRANSACTION_ID, OPERATION_ID + 71, 11);
    RecordingReplay replay = new RecordingReplay();
    IndexedRelationalWalRecovery recovery = new IndexedRelationalWalRecovery(replay);
    check(recovery.apply(
        1_000, record(plan, 0, 0, 1, 1_000), 90, 90, Long.MAX_VALUE, true)
        == StatusCode.CORRUPTION, "covered zero commit sequence was skipped");
    check(recovery.apply(
        2_000, record(plan, 0, -1, 1, 2_000), 90, 90, Long.MAX_VALUE, true)
        == StatusCode.CORRUPTION, "covered negative commit sequence was skipped");
    requireOk(recovery.apply(
        3_000, record(plan, 0, 89, 1, 3_000), 90, 90, Long.MAX_VALUE, true));
    check(replay.applications == 0, "valid covered group reached replay");
  }

  @Test
  void recoveryRejectsDistinctGroupsAtSameNewCommitSequence(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    RecordingReplay replay = new RecordingReplay();
    IndexedWalRecovery recovery = new IndexedWalRecovery(
        wal, null, null, DATABASE, new IndexedStorePhase(), replay);
    requireOk(recovery.recover(GENERATION, true, 90));

    IndexedRelationalWalPlan first = oneBasePlan(
        TRANSACTION_ID, OPERATION_ID + 91, 11);
    IndexedRelationalWalPlan second = oneBasePlan(
        TRANSACTION_ID, OPERATION_ID + 92, 22);
    LocalWalReadResult firstRecord = record(first, 0, 91, 1, 1_000);
    LocalWalReadResult secondRecord = record(second, 0, 91, 1, firstRecord.nextOffset());

    requireOk(recovery.applyRecoveredRecord(1_000, firstRecord, GENERATION));
    check(recovery.applyRecoveredRecord(
        firstRecord.nextOffset(), secondRecord, GENERATION) == StatusCode.CORRUPTION,
        "distinct relational group reused a new commit sequence");
    check(replay.applications == 1, "duplicate frontier group reached replay");
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void walOnlyReopenAppliesRootLifecycleTupleAndBase(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult table = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), table));
    requireOk(created.store().close());

    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    long hash = descriptorHash(descriptor);
    appendRootGroup(wal, descriptor, hash, 2, 0, 4, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_BUILDING, 0, 2, 4, 5);
    appendRootGroup(wal, descriptor, hash, 3, 4, 4, 1, 2,
        IndexedRelationalSuboperations.REGISTRY_BUILDING,
        IndexedRelationalSuboperations.REGISTRY_READY, 2, 0, 5, 5);
    appendTupleInsertGroup(wal, descriptor, hash);
    int[] secondDescriptor = {SqlTypeDescriptor.varchar(16)};
    long secondHash = descriptorHash(secondDescriptor);
    appendRootGroup(wal, secondDescriptor, secondHash, SECOND_OWNER_OBJECT_ID,
        1_001, 1_001, 5, 0, 5, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_BUILDING, 0, 5, 5, 6, 3);
    appendRootGroup(wal, secondDescriptor, secondHash, SECOND_OWNER_OBJECT_ID,
        1_001, 1_001, 6, 5, 5, 1, 2,
        IndexedRelationalSuboperations.REGISTRY_BUILDING,
        IndexedRelationalSuboperations.REGISTRY_READY, 5, 0, 6, 6, 4);
    appendTupleInsertGroup(
        wal, secondDescriptor, secondHash, SECOND_OWNER_OBJECT_ID,
        1_001, 1_001, 5, 7, 3, 5, physicalTextTuple(2, "second"));
    appendBaseInsertGroup(wal, 8, 6);
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistry(reopened.store());
    assertRecoveredRegistry(reopened.store(), 1_001, 5, SECOND_OWNER_OBJECT_ID);
    HeapRowResult base = new HeapRowResult();
    requireOk(reopened.store().fetchByKey(
        CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID),
        1, base));
    check(base.length() == Long.BYTES && base.getLong(0) == 771, "base replay mismatch");
    check(reopened.store().rowCount() == 7, "grouped replay heap frontier mismatch");
    IndexedVacuumResult vacuum = new IndexedVacuumResult();
    requireOk(reopened.store().vacuum(90, vacuum));
    check(vacuum.rowsBefore() == 7 && vacuum.rowsAfter() == 3,
        "tuple leaf entries inflated scalar vacuum retention");
    assertRecoveredRegistry(reopened.store());
    assertRecoveredRegistry(reopened.store(), 1_001, 5, SECOND_OWNER_OBJECT_ID);
    requireOk(reopened.store().fetchByKey(
        CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID),
        1, base));
    check(base.getLong(0) == 771, "vacuum changed grouped base row");
    requireOk(reopened.store().flush());
    appendIncompleteBaseGroup(wal);
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened.reset();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistry(reopened.store());
    assertRecoveredRegistry(reopened.store(), 1_001, 5, SECOND_OWNER_OBJECT_ID);
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

  }

  @Test
  void walOnlyReopenResumesForcedMidDropCleanup(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult opened = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), opened));
    int[] descriptor = {
        SqlTypeDescriptor.varchar(255),
        SqlTypeDescriptor.varchar(255),
        SqlTypeDescriptor.varchar(250)
    };
    long hash = descriptorHash(descriptor);
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 180, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000,
            0, 0, 0, 1, 0, 1,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING, 0, 2, 4, 4),
        new IndexedCommitResult()));
    int tuples = 80;
    long prediction = predictTupleInsert(created.store(), descriptor, 0, 1, tuples, 'w');
    int tupleRoot = (int) (prediction >>> 32);
    int nextPage = (int) prediction;
    check(nextPage > BTreeRootPage.FIRST_REUSABLE_PAGE_ID
            + IndexedTupleGraphReclaimer.MAX_INSPECTED_PAGES,
        "mid-DROP fixture did not leave an unreclaimed suffix");
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 181, liveTupleInsertMutation(
            descriptor, hash, 0, tupleRoot, 4, nextPage, 1, 1, 1, tuples, 'w'),
        new IndexedCommitResult()));
    requireOk(created.store().flush());
    requireOk(created.store().close());

    appendRootGroup(
        wal, descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
        4, tupleRoot, 0, 2, 3,
        IndexedRelationalSuboperations.REGISTRY_BUILDING,
        IndexedRelationalSuboperations.REGISTRY_DROPPING,
        2, 2, nextPage, nextPage, 2);
    int firstCursor = BTreeRootPage.FIRST_REUSABLE_PAGE_ID;
    int recoveredCursor = firstCursor + IndexedTupleGraphReclaimer.MAX_INSPECTED_PAGES;
    appendRootGroup(
        wal, descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
        5, 0, 0, 3, 4,
        IndexedRelationalSuboperations.REGISTRY_DROPPING,
        IndexedRelationalSuboperations.REGISTRY_DROPPING,
        2, 2, nextPage, nextPage, 3, firstCursor, recoveredCursor);
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_DROPPING, 0, 4, 2,
        recoveredCursor);
    IndexedPageSet recovered = pageSet(reopened.store());
    assertRecoveredFreeChain(recovered, recoveredCursor - firstCursor);
    int remainingOwned = 0;
    for (int pageId = recoveredCursor; pageId < nextPage; pageId++) {
      if (recovered.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
          && recovered.ownerKeyId(pageId) == 1_000) remainingOwned++;
    }
    check(remainingOwned == nextPage - recoveredCursor,
        "WAL-only mid-DROP replay lost the remaining owned suffix");

    long transaction = TRANSACTION_ID + 182;
    long generation = 4;
    long heapVersion = 4;
    int cleanupCursor = recoveredCursor;
    while (cleanupCursor < nextPage) {
      int resultingCursor = Math.min(
          nextPage, cleanupCursor + IndexedTupleGraphReclaimer.MAX_INSPECTED_PAGES);
      requireOk(commitRelationalQuiescent(reopened.store(), 
          transaction++, liveRootMutation(
              descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
              0, 0, generation, generation + 1, heapVersion, heapVersion + 1,
              IndexedRelationalMutation.REGISTRY_DROPPING,
              IndexedRelationalMutation.REGISTRY_DROPPING, 2, 2,
              nextPage, nextPage, cleanupCursor, resultingCursor),
          new IndexedCommitResult()));
      generation++;
      heapVersion++;
      cleanupCursor = resultingCursor;
    }
    requireOk(commitRelationalQuiescent(reopened.store(), 
        transaction, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
            0, 0, generation, generation + 1, heapVersion, heapVersion + 1,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            IndexedRelationalMutation.REGISTRY_ABSENT, 2, 0,
            nextPage, nextPage, cleanupCursor, 0),
        new IndexedCommitResult()));
    generation++;
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_ABSENT, 0, generation, 0, 0);
    assertRecoveredFreeChain(pageSet(reopened.store()), nextPage - firstCursor);
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void liveGroupedCommitRejectsBeforeWalThenRetriesAndReopens(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult table = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), table));
    IndexedCommitResult commit = new IndexedCommitResult();
    IndexedLogicalRowIdReservation reserved = new IndexedLogicalRowIdReservation();
    requireOk(created.store().admitLogicalRowIds(OWNER_OBJECT_ID, 1));
    requireOk(created.store().reserveLogicalRowIds(OWNER_OBJECT_ID, 1, reserved));

    IndexedRelationalMutation invalid = liveBaseMutation(99, 811);
    check(commitRelationalQuiescent(created.store(), TRANSACTION_ID, invalid, commit)
        == StatusCode.CORRUPTION, "invalid live evidence reached WAL publication");
    check(created.store().rowCount() == 0 && created.store().currentCommitSequence() == 1,
        "failed live group changed current state");

    IndexedRelationalMutation valid = liveBaseMutation(SCALAR_ROOT, 811);
    requireOk(commitRelationalQuiescent(created.store(), TRANSACTION_ID, valid, commit));
    check(commit.commitSequence() == 2 && created.store().rowCount() == 1,
        "live grouped commit did not publish one frontier");
    HeapRowResult row = new HeapRowResult();
    long space = CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID);
    requireOk(created.store().fetchByKey(space, 1, row));
    check(row.getLong(0) == 811, "live grouped base row mismatch");
    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    requireOk(reopened.store().fetchByKey(space, 1, row));
    check(row.getLong(0) == 811 && reopened.store().rowCount() == 1,
        "live grouped recovery diverged from publication");
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void liveGroupedCommitPublishesBaseAndTupleRootTogether(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult table = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), table));
    IndexedCommitResult commit = new IndexedCommitResult();
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    long hash = descriptorHash(descriptor);

    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            0, 4, 0, 1, 0, 1,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING, 0, TRANSACTION_ID, 4, 5), commit));
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 1, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            4, 4, 1, 2, 1, 2,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_READY, TRANSACTION_ID, 0, 5, 5), commit));

    ByteBuffer tuple = physicalFixedTuple(1, 991);
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    row.putLong(0, 991);
    IndexedLogicalRowIdReservation reserved = new IndexedLogicalRowIdReservation();
    requireOk(created.store().admitLogicalRowIds(OWNER_OBJECT_ID, 1));
    requireOk(created.store().reserveLogicalRowIds(OWNER_OBJECT_ID, 1, reserved));
    IndexedRelationalMutation group = new IndexedRelationalMutation(2, 1, 1);
    requireOk(group.reserve(2, 1, 1, Long.BYTES + tuple.remaining()));
    requireOk(group.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, hash, descriptor, 0, 1));
    requireOk(group.appendLogicalRowFloor(OWNER_OBJECT_ID, 2));
    requireOk(group.appendSuboperation(
        OWNER_OBJECT_ID, -1, 0, 1, 0, 0, 3, 3, 5, 5,
        0, 0, 2, 3, IndexedRelationalMutation.REGISTRY_ABSENT,
        IndexedRelationalMutation.REGISTRY_ABSENT, 0, 0));
    requireOk(group.appendSuboperation(
        OWNER_OBJECT_ID, 0, 1, 1, 4, 4, 3, 3, 5, 5,
        2, 3, 3, 4, IndexedRelationalMutation.REGISTRY_READY,
        IndexedRelationalMutation.REGISTRY_READY, 0, 0));
    requireOk(group.appendBase(
        0, OWNER_OBJECT_ID, IndexedRelationalMutation.BASE_INSERT,
        1, 0, row, 0, Long.BYTES));
    requireOk(group.appendTuple(
        1, OWNER_OBJECT_ID, IndexedRelationalMutation.TUPLE_INSERT,
        0, 1, tuple, tuple.position(), tuple.remaining()));
    requireOk(group.seal());
    requireOk(commitRelationalQuiescent(created.store(), TRANSACTION_ID + 2, group, commit));
    check(commit.commitSequence() == 4 && created.store().rowCount() == 4,
        "base-and-tuple group did not publish one frontier");
    assertRecoveredRegistry(
        created.store(), 1_000, 4, OWNER_OBJECT_ID, 3, KEY_SCHEMA_ID);
    HeapRowResult fetched = new HeapRowResult();
    requireOk(created.store().fetchByKey(
        CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID),
        1, fetched));
    check(fetched.getLong(0) == 991, "atomic base row missing");
    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult resumed = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(
        directory, wal, DATABASE, GENERATION, databaseProviderLease(4), resumed));
    created = resumed;
    assertRecoveredRegistry(
        created.store(), 1_000, 4, OWNER_OBJECT_ID, 3, KEY_SCHEMA_ID);

    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 3, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            4, 4, 3, 4, 4, 5,
            IndexedRelationalMutation.REGISTRY_READY,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            0, TRANSACTION_ID + 3, 5, 5), commit));
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 4, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            4, 0, 4, 5, 5, 6,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            TRANSACTION_ID + 3, TRANSACTION_ID + 3, 5, 5), commit));
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 5, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            0, 0, 5, 6, 6, 7,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            TRANSACTION_ID + 3, TRANSACTION_ID + 3, 5, 5), commit));
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 6, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            0, 0, 6, 7, 7, 8,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            TRANSACTION_ID + 3, 0, 5, 5), commit));
    IndexedPageSet livePages = pageSet(created.store());
    check(livePages.payloadKind(4) == PageCodec.PAYLOAD_KIND_FREE
        && BTreeRootPage.freePageCount(livePages.currentPayloadUnchecked(2)) == 1,
        "DROP did not publish durable free identity");

    int[] second = {SqlTypeDescriptor.varchar(16)};
    long secondHash = descriptorHash(second);
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 7, liveRootMutation(
            second, secondHash, SECOND_OWNER_OBJECT_ID, 1_001,
            0, 4, 0, 1, 8, 9,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING, 0, TRANSACTION_ID + 7, 5, 5), commit));
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 8, liveRootMutation(
            second, secondHash, SECOND_OWNER_OBJECT_ID, 1_001,
            4, 4, 1, 2, 9, 10,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_READY, TRANSACTION_ID + 7, 0, 5, 5), commit));
    check(livePages.payloadKind(4) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
        && livePages.ownerKeyId(4) == 1_001
        && BTreeRootPage.freePageCount(livePages.currentPayloadUnchecked(2)) == 0,
        "recreated index did not consume durable free page");
    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistry(reopened.store(), 1_001, 4, SECOND_OWNER_OBJECT_ID, 2);
    check(reopened.store().rowCount() == 10, "atomic grouped recovery frontier mismatch");
    IndexedPageSet reopenedPages = pageSet(reopened.store());
    check(reopenedPages.payloadKind(4) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
        && reopenedPages.ownerKeyId(4) == 1_001,
        "reopen lost reused tuple-page identity");
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void sessionHybridCommitDerivesEvidenceAndPublishesOneGroup(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), tableResult));
    IndexedCommitResult commit = new IndexedCommitResult();
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    long hash = descriptorHash(descriptor);
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            0, 4, 0, 1, 0, 1,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING, 0, TRANSACTION_ID, 4, 5), commit));
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 1, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            4, 4, 1, 2, 1, 2,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_READY, TRANSACTION_ID, 0, 5, 5), commit));

    IndexedTable table = tableResult.table();
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext context = context(manager, table, null, vacuum);
    IndexedTransactionSession session = session(context, 128);
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    long baseSpace = CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID);
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    row.putLong(0, 991);
    requireOk(session.insert(baseSpace, 1, row));
    ByteBuffer tuple = physicalFixedTuple(1, 991);
    requireOk(session.preflightTupleMutations(1, 1, tuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), 1,
        tuple, tuple.position(), tuple.remaining()));
    TransactionOutcome outcome = new TransactionOutcome();
    requireOk(session.commit(outcome));

    HeapRowResult fetched = new HeapRowResult();
    requireOk(created.store().fetchByKey(baseSpace, 1, fetched));
    check(fetched.getLong(0) == 991, "hybrid base row missing");
    assertRecoveredRegistry(
        created.store(), 1_000, 4, OWNER_OBJECT_ID, 3, KEY_SCHEMA_ID);
    check(created.store().rowCount() == 4,
        "hybrid base and registry did not publish one heap frontier");
    IndexedSavepoint savepoint = new IndexedSavepoint();
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    requireOk(session.createSavepoint(savepoint));
    ByteBuffer secondRow = ByteBuffer.allocate(Long.BYTES);
    secondRow.putLong(0, 992);
    requireOk(session.insert(baseSpace, 2, secondRow));
    ByteBuffer secondTuple = physicalFixedTuple(2, 992);
    requireOk(session.preflightTupleMutations(1, 1, secondTuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), 2,
        secondTuple, secondTuple.position(), secondTuple.remaining()));
    requireOk(session.fetchByKey(baseSpace, 2, fetched));
    requireOk(session.rollbackToSavepoint(savepoint));
    check(session.fetchByKey(baseSpace, 2, fetched) == StatusCode.CONFLICT,
        "savepoint rollback retained a scalar overlay row");
    requireOk(session.commit(outcome));
    check(outcome.state() == TransactionState.COMMITTED,
        "rolled-back hybrid transaction did not commit read-only");
    assertRecoveredRegistry(
        created.store(), 1_000, 4, OWNER_OBJECT_ID, 3, KEY_SCHEMA_ID);

    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    requireOk(session.insert(baseSpace, 2, secondRow));
    requireOk(session.preflightTupleMutations(1, 1, secondTuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), 2,
        secondTuple, secondTuple.position(), secondTuple.remaining()));
    requireOk(session.abort(outcome));
    check(outcome.state() == TransactionState.ABORTED,
        "hybrid abort did not report ABORTED");
    check(created.store().fetchByKey(baseSpace, 2, fetched) == StatusCode.CONFLICT,
        "hybrid abort published a base row");
    assertRecoveredRegistry(
        created.store(), 1_000, 4, OWNER_OBJECT_ID, 3, KEY_SCHEMA_ID);

    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(
        directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    requireOk(reopened.store().fetchByKey(baseSpace, 1, fetched));
    check(fetched.getLong(0) == 991, "reopen lost hybrid base row");
    check(reopened.store().fetchByKey(baseSpace, 2, fetched) == StatusCode.CONFLICT,
        "reopen recovered rolled-back hybrid row");
    assertRecoveredRegistry(
        reopened.store(), 1_000, 4, OWNER_OBJECT_ID, 3, KEY_SCHEMA_ID);
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void concurrentHybridSessionsShareOneForceAndRecoverIndependentDecisions(
      @TempDir Path root) throws Exception {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(root, counters);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), tableResult));
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    long hash = descriptorHash(descriptor);
    IndexedCommitResult commit = new IndexedCommitResult();
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            0, 4, 0, 1, 0, 1,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING, 0, TRANSACTION_ID, 4, 5), commit));
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 1, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            4, 4, 1, 2, 1, 2,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_READY, TRANSACTION_ID, 0, 5, 5), commit));

    IndexedTable table = tableResult.table();
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedGroupCommitCoordinator coordinator =
        new IndexedGroupCommitCoordinator(manager, table, 500_000_000);
    IndexedSessionContext context = context(manager, table, coordinator, vacuum);
    IndexedTransactionSession first = session(context, 128);
    IndexedTransactionSession second = session(context, 128);
    long baseSpace = CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID);
    prepareHybrid(first, descriptor, baseSpace, 1, 991);
    prepareHybrid(second, descriptor, baseSpace, 2, 992);
    check(first.eligibleForCommitGroup(), "first hybrid transaction was not group eligible");
    check(second.eligibleForCommitGroup(), "second hybrid transaction was not group eligible");
    check(first.hasTupleIntents() && second.hasTupleIntents(), "hybrid intents were not retained");
    requireOk(first.prepareLogicalCommit());
    requireOk(second.prepareLogicalCommit());
    TransactionOutcome[] probeOutcomes = {new TransactionOutcome(), new TransactionOutcome()};
    io.riverdb.tx.Transaction[] probeTransactions = {
        first.groupTransaction(), second.groupTransaction()
    };
    requireOk(manager.prepareCommit(probeTransactions[0], probeOutcomes[0]));
    requireOk(manager.prepareCommit(probeTransactions[1], probeOutcomes[1]));
    IndexedPreparedLogicalCommit[] cohort = {
        first.preparedCommit(), second.preparedCommit()
    };
    requireOk(table.reserveHybridCommitGroupCapacity(cohort.length));
    StatusCode preflight = table.preflightHybridCommitGroup(
        cohort, cohort.length, Long.MAX_VALUE);
    check(preflight.isOk(), "hybrid cohort preflight failed: " + preflight);
    requireOk(table.cancelCommitGroup());
    check(manager.abortPreparedCommitGroup(
        probeTransactions, probeOutcomes, probeTransactions.length,
        StatusCode.CANCELLED) == StatusCode.OK,
        "prepared probe cohort was not aborted");
    check(first.completeCoordinatedCommit(StatusCode.CANCELLED) == StatusCode.CANCELLED,
        "first probe cleanup failed");
    check(second.completeCoordinatedCommit(StatusCode.CANCELLED) == StatusCode.CANCELLED,
        "second probe cleanup failed");
    prepareHybrid(first, descriptor, baseSpace, 1, 991);
    prepareHybrid(second, descriptor, baseSpace, 2, 992);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    TransactionOutcome firstOutcome = new TransactionOutcome();
    TransactionOutcome secondOutcome = new TransactionOutcome();
    long forces = counters.forceCalls();
    try {
      Future<StatusCode> firstCommit = executor.submit(
          () -> coordinatedCommit(first, firstOutcome, ready, start));
      Future<StatusCode> secondCommit = executor.submit(
          () -> coordinatedCommit(second, secondOutcome, ready, start));
      ready.await();
      start.countDown();
      requireOk(firstCommit.get());
      requireOk(secondCommit.get());
    } finally {
      executor.shutdownNow();
    }
    check(counters.forceCalls() == forces + 1,
        "hybrid cohort force delta " + (counters.forceCalls() - forces));
    check(firstOutcome.state() == TransactionState.COMMITTED
            && secondOutcome.state() == TransactionState.COMMITTED
            && firstOutcome.commitSequence() != secondOutcome.commitSequence()
            && Math.abs(firstOutcome.commitSequence() - secondOutcome.commitSequence()) == 1,
        "hybrid cohort did not retain independent consecutive decisions");
    HeapRowResult fetched = new HeapRowResult();
    requireOk(created.store().fetchByKey(baseSpace, 1, fetched));
    check(fetched.getLong(0) == 991, "first grouped hybrid row missing");
    requireOk(created.store().fetchByKey(baseSpace, 2, fetched));
    check(fetched.getLong(0) == 992, "second grouped hybrid row missing");
    assertTuple(created.store(), descriptor, 991, 1);
    assertTuple(created.store(), descriptor, 992, 2);
    assertRecoveredRegistry(created.store(), 1_000, 4, OWNER_OBJECT_ID, 4, KEY_SCHEMA_ID);
    IndexedGroupCommitTelemetry telemetry = new IndexedGroupCommitTelemetry();
    requireOk(coordinator.copyTelemetry(telemetry));
    check(telemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_FORCE) == 1,
        "coordinator did not record the shared force");
    check(telemetry.successfulCohortSizeBucket(1) == 1,
        "coordinator did not retain both requests");
    requireOk(coordinator.close());

    crashWal(wal);
    requireOk(directory.close());
    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(
        directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    requireOk(reopened.store().fetchByKey(baseSpace, 1, fetched));
    requireOk(reopened.store().fetchByKey(baseSpace, 2, fetched));
    assertTuple(reopened.store(), descriptor, 991, 1);
    assertTuple(reopened.store(), descriptor, 992, 2);
    assertRecoveredRegistry(reopened.store(), 1_000, 4, OWNER_OBJECT_ID, 4, KEY_SCHEMA_ID);
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened.reset();
    requireOk(IndexedTableStore.openExisting(
        directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    requireOk(reopened.store().fetchByKey(baseSpace, 1, fetched));
    requireOk(reopened.store().fetchByKey(baseSpace, 2, fetched));
    assertTuple(reopened.store(), descriptor, 991, 1);
    assertTuple(reopened.store(), descriptor, 992, 2);
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void tupleLeafSplitPublishesInsideTwoMemberHybridGroupAndLeavesStoreReusable(
      @TempDir Path root) throws Exception {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(root, counters);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), tableResult));
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    long hash = descriptorHash(descriptor);
    IndexedCommitResult rootCommit = new IndexedCommitResult();
    requireOk(commitRelationalQuiescent(created.store(),
        TRANSACTION_ID, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            0, 4, 0, 1, 0, 1,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING, 0, TRANSACTION_ID, 4, 5),
        rootCommit));
    requireOk(commitRelationalQuiescent(created.store(),
        TRANSACTION_ID + 1, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            4, 4, 1, 2, 1, 2,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_READY, TRANSACTION_ID, 0, 5, 5),
        rootCommit));

    IndexedTable table = tableResult.table();
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext context = context(manager, table, null, vacuum);
    long baseSpace = CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID);
    IndexedTransactionSession filler = session(context, 128);
    TransactionOutcome fillerOutcome = new TransactionOutcome();
    int splitKey = 1;
    long splitValue;
    int splitNewPages;
    while (true) {
      splitValue = splitKey;
      ByteBuffer candidate = physicalFixedTuple(splitKey, splitValue);
      splitNewPages = tupleInsertNewPageCount(created.store(), descriptor, candidate);
      if (splitNewPages > 0) break;
      prepareHybrid(filler, descriptor, baseSpace, splitKey, splitValue);
      requireOk(filler.commit(fillerOutcome));
      check(fillerOutcome.state() == TransactionState.COMMITTED,
          "tuple leaf filler did not commit");
      splitKey++;
    }
    check(splitKey > 1, "empty tuple leaf unexpectedly required a split");
    requireOk(filler.close());

    TupleIndexRootRecord beforeGroup = registryRecord(created.store(), 1_000);
    check(beforeGroup.rootPageId() == 4,
        "tuple root changed before allocating preflight boundary");
    int tuplePagesBefore = tuplePageCount(created.store(), 1_000);
    int firstKey = splitKey;
    long firstValue = splitValue;
    int secondKey = splitKey + 1;
    long secondValue = splitValue + 1;
    IndexedTransactionSession first = session(context, 128);
    IndexedTransactionSession second = session(context, 128);
    prepareHybrid(first, descriptor, baseSpace, firstKey, firstValue);
    prepareHybrid(second, descriptor, baseSpace, secondKey, secondValue);
    int firstMask = first.commitGroupEligibilityMask();
    int secondMask = second.commitGroupEligibilityMask();
    check(firstMask == 0 && secondMask == 0,
        "split cohort was not group eligible");

    IndexedGroupCommitMetrics metrics = table.commitMetrics();
    IndexedGroupCommitTelemetry beforeTelemetry = new IndexedGroupCommitTelemetry();
    requireOk(table.copyCommitTelemetry(beforeTelemetry));
    TransactionOutcome firstOutcome = new TransactionOutcome();
    TransactionOutcome secondOutcome = new TransactionOutcome();
    IndexedGroupCommitRequest firstRequest = new IndexedGroupCommitRequest(first);
    IndexedGroupCommitRequest secondRequest = new IndexedGroupCommitRequest(second);
    requireOk(first.prepareLogicalCommit());
    requireOk(second.prepareLogicalCommit());
    long firstTicket = firstRequest.prepare(firstOutcome, firstMask, metrics);
    long secondTicket = secondRequest.prepare(secondOutcome, secondMask, metrics);
    check(firstTicket > 0 && secondTicket > 0,
        "split cohort requests were not prepared");
    requireOk(manager.prepareCommit(first.groupTransaction(), firstRequest.outcome));
    requireOk(manager.prepareCommit(second.groupTransaction(), secondRequest.outcome));
    // Mirror process() admission while retaining force/publication as explicit test phases.
    metrics.recordWriteSubmission(firstMask, true);
    metrics.recordWriteSubmission(secondMask, true);
    metrics.recordQueueEnqueue(1);
    metrics.recordQueueEnqueue(2);
    metrics.recordWriterSelection(2, 2, 2, true, false);
    metrics.recordAttemptedGroup(2);
    IndexedGroupCommitBatch batch = new IndexedGroupCommitBatch(manager, table, metrics);
    requireOk(table.reserveHybridCommitGroupCapacity(batch.capacity()));
    batch.add(0, firstRequest);
    batch.add(1, secondRequest);

    long forceCalls = counters.forceCalls();
    check(batch.forceSharedGroup(2),
        "split cohort failed before forced publication");
    check(counters.forceCalls() == forceCalls + 1,
        "split cohort did not use exactly one shared force");
    check(table.commitGroupDecisionAppended(),
        "forced split cohort did not retain its WAL decision");
    IndexedGroupCommitTelemetry forcedTelemetry = new IndexedGroupCommitTelemetry();
    requireOk(table.copyCommitTelemetry(forcedTelemetry));
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_PREFLIGHT) == 1,
        "split cohort preflight phase was not recorded");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.PREFLIGHT_RECLAIM) == 1,
        "split cohort reclaim phase was not recorded");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.PREFLIGHT_VERSION_RESERVATION) == 1,
        "split cohort version-reservation phase was not recorded");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.PREFLIGHT_COMPILE) == 2,
        "split cohort did not compile both members");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.PREFLIGHT_WAL_PLAN) == 2,
        "split cohort did not plan both WAL members");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.PREFLIGHT_LOGICAL_ROW_ADMISSION) == 2,
        "split cohort did not admit both logical-row updates");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.PREFLIGHT_WAL_ADMISSION) == 2,
        "split cohort did not admit both WAL members");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.PREFLIGHT_PAGE_FREEZE) == 2,
        "split cohort did not freeze both member generations");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.PREFLIGHT_OPERATION_ADMISSION) == 1,
        "split cohort publication was not admitted");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_ADMISSION) == 1,
        "split cohort transaction admission was not recorded");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_APPEND) == 1,
        "split cohort append phase was not recorded");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_FORCE) == 1,
        "split cohort force phase was not recorded");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_PUBLICATION) == 0,
        "split cohort published before the explicit publication phase");

    batch.publishForced(2);
    check(firstRequest.outcome.state() == TransactionState.COMMITTED
            && secondRequest.outcome.state() == TransactionState.COMMITTED,
        "split cohort publication did not commit both members");
    check(!table.commitGroupDecisionAppended(),
        "split cohort retained group state after publication");
    batch.complete(2);
    StatusCode firstStatus = firstRequest.await(firstTicket, firstOutcome);
    StatusCode secondStatus = secondRequest.await(secondTicket, secondOutcome);
    requireOk(firstStatus);
    requireOk(secondStatus);
    requireOk(first.completeCoordinatedCommit(firstStatus));
    requireOk(second.completeCoordinatedCommit(secondStatus));
    check(firstOutcome.state() == TransactionState.COMMITTED
            && secondOutcome.state() == TransactionState.COMMITTED
            && secondOutcome.commitSequence() == firstOutcome.commitSequence() + 1,
        "split cohort did not retain two consecutive commit decisions");

    IndexedGroupCommitTelemetry publishedTelemetry = new IndexedGroupCommitTelemetry();
    requireOk(table.copyCommitTelemetry(publishedTelemetry));
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_PUBLICATION) == 1,
        "split cohort publication phase was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_PUBLICATION_PREPARE) == 1,
        "split cohort publication preparation was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_PUBLICATION_INSTALL) == 1,
        "split cohort page/frontier installation was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_TRANSACTION_COMPLETION) == 1,
        "split cohort transaction completion was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_LOCK_RELEASE) == 1,
        "split cohort lock release was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_LOCK_OUTCOME) == 1,
        "split cohort lock outcome was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_LOCK_REQUEST_CANCELLATION) == 1,
        "split cohort lock-request cancellation was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_LOCK_HOLDING_RELEASE) == 1,
        "split cohort holding release was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_LOCK_RECORD_RECYCLE) == 1,
        "split cohort lock-record recycle was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_ACTIVE_REMOVAL) == 1,
        "split cohort active-set removal was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP,
        IndexedCommitStage.GROUP_OUTCOME_PUBLICATION) == 1,
        "split cohort outcome publication was not recorded");
    check(publishedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.NOTIFICATION) == 2,
        "split cohort did not notify both members");
    check(publishedTelemetry.successfulCohortSizeBucket(1) == 1
            && publishedTelemetry.maximumSuccessfulCohort() == 2,
        "split cohort was not reported as one successful size-two group");
    check(publishedTelemetry.directCommitTransactions()
            == beforeTelemetry.directCommitTransactions(),
        "split cohort used a direct fallback");
    for (IndexedGroupFailureStage stage : IndexedGroupFailureStage.values()) {
      check(publishedTelemetry.groupFailureCohortCount(stage) == 0,
          "split cohort recorded group failure at " + stage);
    }
    for (IndexedCommitStage stage : IndexedCommitStage.values()) {
      check(publishedTelemetry.stageFailureCount(
          IndexedCommitPath.SHARED_GROUP, stage, StatusCode.INVARIANT_BROKEN) == 0,
          "split cohort recorded invariant failure at " + stage);
    }
    check(publishedTelemetry.reconciles(),
        "split cohort telemetry did not reconcile");

    TupleIndexRootRecord afterGroup = registryRecord(created.store(), 1_000);
    check(afterGroup.rootPageId() != beforeGroup.rootPageId()
            && afterGroup.generation() == beforeGroup.generation() + 2,
        "allocating tuple insert did not replace the leaf root");
    int tuplePagesAfterGroup = tuplePageCount(created.store(), 1_000);
    check(tuplePagesAfterGroup == tuplePagesBefore + splitNewPages,
        "split cohort allocated a different tuple-page count than preflight");
    assertBaseRow(created.store(), baseSpace, firstKey, firstValue);
    assertBaseRow(created.store(), baseSpace, secondKey, secondValue);
    assertTuple(created.store(), descriptor, firstValue, firstKey);
    assertTuple(created.store(), descriptor, secondValue, secondKey);
    requireOk(first.close());
    requireOk(second.close());
    check(manager.activeTransactionCount() == 0
            && manager.activeLockCount() == 0
            && manager.waitingLockCount() == 0,
        "split cohort did not clean up transaction or lock state");
    requireOk(created.store().admission());

    int thirdKey = splitKey + 2;
    long thirdValue = splitValue + 2;
    check(tupleInsertNewPageCount(
        created.store(), descriptor, physicalFixedTuple(thirdKey, thirdValue)) == 0,
        "freshly split tuple leaf did not admit the independent commit");
    IndexedTransactionSession third = session(context, 128);
    TransactionOutcome thirdOutcome = new TransactionOutcome();
    prepareHybrid(third, descriptor, baseSpace, thirdKey, thirdValue);
    requireOk(third.commit(thirdOutcome));
    check(thirdOutcome.state() == TransactionState.COMMITTED
            && thirdOutcome.commitSequence() == secondOutcome.commitSequence() + 1,
        "independent commit failed after split-cohort cleanup");
    requireOk(third.close());
    check(manager.activeTransactionCount() == 0
            && manager.activeLockCount() == 0
            && manager.waitingLockCount() == 0,
        "independent commit did not clean up transaction or lock state");
    requireOk(created.store().admission());
    check(tuplePageCount(created.store(), 1_000) == tuplePagesAfterGroup,
        "independent commit unexpectedly allocated another tuple page");
    assertBaseRow(created.store(), baseSpace, thirdKey, thirdValue);
    assertTuple(created.store(), descriptor, thirdValue, thirdKey);
    int lastFillerKey = splitKey - 1;
    long lastFillerValue = lastFillerKey;
    assertBaseRow(created.store(), baseSpace, lastFillerKey, lastFillerValue);
    assertTuple(created.store(), descriptor, lastFillerValue, lastFillerKey);
    TupleIndexRootRecord finalRegistry = registryRecord(created.store(), 1_000);
    check(finalRegistry.generation() == afterGroup.generation() + 1,
        "independent commit did not advance tuple generation exactly once");

    crashWal(wal);
    requireOk(directory.close());
    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(
        directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertBaseRow(reopened.store(), baseSpace, lastFillerKey, lastFillerValue);
    assertBaseRow(reopened.store(), baseSpace, firstKey, firstValue);
    assertBaseRow(reopened.store(), baseSpace, secondKey, secondValue);
    assertBaseRow(reopened.store(), baseSpace, thirdKey, thirdValue);
    assertTuple(reopened.store(), descriptor, lastFillerValue, lastFillerKey);
    assertTuple(reopened.store(), descriptor, firstValue, firstKey);
    assertTuple(reopened.store(), descriptor, secondValue, secondKey);
    assertTuple(reopened.store(), descriptor, thirdValue, thirdKey);
    assertRecoveredRegistry(
        reopened.store(), 1_000, finalRegistry.rootPageId(), OWNER_OBJECT_ID,
        finalRegistry.generation(), KEY_SCHEMA_ID);
    check(tuplePageCount(reopened.store(), 1_000) == tuplePagesAfterGroup,
        "reopen recovered a different tuple-page graph");
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void tupleDeleteAfterSavepointRollbackRetainsEarlierInsert(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), tableResult));
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    long hash = descriptorHash(descriptor);
    IndexedCommitResult commit = new IndexedCommitResult();
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            0, 4, 0, 1, 0, 1,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING, 0, TRANSACTION_ID, 4, 5), commit));
    requireOk(commitRelationalQuiescent(created.store(), 
        TRANSACTION_ID + 1, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
            4, 4, 1, 2, 1, 2,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_READY, TRANSACTION_ID, 0, 5, 5), commit));

    IndexedTable table = tableResult.table();
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext context = context(manager, table, null, vacuum);
    IndexedTransactionSession session = session(context, 128);
    long baseSpace = CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID);
    ByteBuffer row = scalarRow(993);
    ByteBuffer tuple = physicalFixedTuple(3, 993);
    IndexedSavepoint savepoint = new IndexedSavepoint();
    TransactionOutcome outcome = new TransactionOutcome();
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    requireOk(session.insert(baseSpace, 3, row));
    requireOk(session.preflightTupleMutations(1, 1, tuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), 3,
        tuple, tuple.position(), tuple.remaining()));
    requireOk(session.createSavepoint(savepoint));
    requireOk(session.delete(baseSpace, 3));
    requireOk(session.preflightTupleMutations(1, 0, tuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_DELETE,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), 3,
        tuple, tuple.position(), tuple.remaining()));
    requireOk(session.rollbackToSavepoint(savepoint));
    requireOk(session.commit(outcome));

    ByteBuffer user = genericFixedTuple(993);
    IndexedTupleProbeResult probe = new IndexedTupleProbeResult();
    requireOk(created.store().probeTuplePrefixAt(
        created.store().currentCommitSequence(), OWNER_OBJECT_ID, 1_000,
        KEY_SCHEMA_ID, shape(descriptor), user, 0, user.remaining(), probe));
    check(probe.found() && probe.logicalRowId() == 3,
        "savepoint rollback lost the earlier tuple insert");
    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    probe.reset();
    requireOk(reopened.store().probeTuplePrefixAt(
        reopened.store().currentCommitSequence(), OWNER_OBJECT_ID, 1_000,
        KEY_SCHEMA_ID, shape(descriptor), user, 0, user.remaining(), probe));
    check(probe.found() && probe.logicalRowId() == 3,
        "reopen lost the savepoint-retained tuple insert");
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void sessionAtomicallyPublishesScalarRowsAndTwoTupleLifecycles(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), tableResult));
    IndexedTable table = tableResult.table();
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext context = context(manager, table, null, vacuum);
    IndexedTransactionSession session = session(context, 128);
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    TransactionOutcome outcome = new TransactionOutcome();

    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    requireOk(session.insert(77, 1, scalarRow(701)));
    requireOk(session.preflightTupleIndexLifecycles(2));
    requireOk(session.stageTupleIndexBuilding(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, OWNER_OBJECT_ID, shape(descriptor)));
    requireOk(session.stageTupleIndexBuilding(
        SECOND_OWNER_OBJECT_ID, 1_001, KEY_SCHEMA_ID,
        SECOND_OWNER_OBJECT_ID, shape(descriptor)));
    requireOk(session.commit(outcome));
    check(outcome.state() == TransactionState.COMMITTED,
        "batched BUILDING transition did not commit");
    TupleIndexRootRecord firstBuilding = registryRecord(created.store(), 1_000);
    TupleIndexRootRecord secondBuilding = registryRecord(created.store(), 1_001);
    check(firstBuilding.state() == TupleIndexRootRecordCodec.STATE_BUILDING
        && secondBuilding.state() == TupleIndexRootRecordCodec.STATE_BUILDING
        && firstBuilding.rootPageId() > 0 && secondBuilding.rootPageId() > 0
        && firstBuilding.rootPageId() != secondBuilding.rootPageId(),
        "batched BUILDING roots were not distinct and private");

    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    requireOk(session.preflightTupleIndexLifecycles(1));
    requireOk(session.stageTupleIndexBuildingBatch(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, OWNER_OBJECT_ID, shape(descriptor)));
    requireOk(session.commit(outcome));
    TupleIndexRootRecord progressed = registryRecord(created.store(), 1_000);
    check(progressed.state() == TupleIndexRootRecordCodec.STATE_BUILDING
        && progressed.rootPageId() == firstBuilding.rootPageId()
        && progressed.generation() == firstBuilding.generation() + 1,
        "empty BUILDING progress transition changed the private root");

    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    requireOk(session.insert(77, 2, scalarRow(702)));
    requireOk(session.preflightTupleIndexLifecycles(2));
    requireOk(session.stageTupleIndexReady(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, OWNER_OBJECT_ID, shape(descriptor)));
    requireOk(session.stageTupleIndexReady(
        SECOND_OWNER_OBJECT_ID, 1_001, KEY_SCHEMA_ID,
        SECOND_OWNER_OBJECT_ID, shape(descriptor)));
    requireOk(session.commit(outcome));
    check(outcome.state() == TransactionState.COMMITTED,
        "batched READY transition did not commit");
    assertReadyRegistry(
        created.store(), 1_000, OWNER_OBJECT_ID, firstBuilding.rootPageId(), 3);
    assertReadyRegistry(
        created.store(), 1_001, SECOND_OWNER_OBJECT_ID, secondBuilding.rootPageId(), 2);
    HeapRowResult scalar = new HeapRowResult();
    requireOk(created.store().fetchByKey(77, 1, scalar));
    requireOk(created.store().fetchByKey(77, 2, scalar));

    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertReadyRegistry(
        reopened.store(), 1_000, OWNER_OBJECT_ID, firstBuilding.rootPageId(), 3);
    assertReadyRegistry(
        reopened.store(), 1_001, SECOND_OWNER_OBJECT_ID, secondBuilding.rootPageId(), 2);
    requireOk(reopened.store().fetchByKey(77, 1, scalar));
    requireOk(reopened.store().fetchByKey(77, 2, scalar));

    IndexedTableOpenResult reopenedTable = new IndexedTableOpenResult();
    requireOk(IndexedTable.open(reopened.store(), reopenedTable));
    manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), reopenedTable.table().nextTransactionId(), 4);
    vacuum = new IndexedVacuum(manager, reopenedTable.table());
    context = context(manager, reopenedTable.table(), null, vacuum);
    session = session(context, 128);
    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    int cleanupHorizon = session.tupleIndexCleanupHorizon();
    requireOk(session.preflightTupleIndexLifecycles(2));
    requireOk(session.stageTupleIndexDropping(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, OWNER_OBJECT_ID, shape(descriptor)));
    requireOk(session.stageTupleIndexDropping(
        SECOND_OWNER_OBJECT_ID, 1_001, KEY_SCHEMA_ID,
        SECOND_OWNER_OBJECT_ID, shape(descriptor)));
    requireOk(session.commit(outcome));
    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    requireOk(session.preflightTupleIndexLifecycles(1));
    requireOk(session.stageTupleIndexBuilding(
        OWNER_OBJECT_ID, 1_002, KEY_SCHEMA_ID, OWNER_OBJECT_ID, shape(descriptor)));
    requireOk(session.commit(outcome));
    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    requireOk(session.preflightTupleIndexLifecycles(2));
    requireOk(session.stageTupleIndexReclaim(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, OWNER_OBJECT_ID,
        shape(descriptor), cleanupHorizon));
    requireOk(session.stageTupleIndexReclaim(
        SECOND_OWNER_OBJECT_ID, 1_001, KEY_SCHEMA_ID,
        SECOND_OWNER_OBJECT_ID, shape(descriptor), cleanupHorizon));
    requireOk(session.commit(outcome));
    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    requireOk(session.preflightTupleIndexLifecycles(2));
    requireOk(session.stageTupleIndexAbsent(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, OWNER_OBJECT_ID,
        shape(descriptor), cleanupHorizon));
    requireOk(session.stageTupleIndexAbsent(
        SECOND_OWNER_OBJECT_ID, 1_001, KEY_SCHEMA_ID,
        SECOND_OWNER_OBJECT_ID, shape(descriptor), cleanupHorizon));
    requireOk(session.commit(outcome));
    assertAbsentRegistry(reopened.store(), 1_000, OWNER_OBJECT_ID, 6);
    assertAbsentRegistry(reopened.store(), 1_001, SECOND_OWNER_OBJECT_ID, 5);
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult cleaned = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), cleaned));
    assertAbsentRegistry(cleaned.store(), 1_000, OWNER_OBJECT_ID, 6);
    assertAbsentRegistry(cleaned.store(), 1_001, SECOND_OWNER_OBJECT_ID, 5);
    requireOk(cleaned.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void sessionPublishesBuildingRootWithSameTransactionTupleDml(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult opened = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), opened));
    IndexedTable table = opened.table();
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext context = context(manager, table, null, vacuum);
    IndexedTransactionSession session = session(context, 128);
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    TupleShape shape = shape(descriptor);
    TransactionOutcome outcome = new TransactionOutcome();

    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    requireOk(session.preflightTupleIndexLifecycles(1));
    requireOk(session.stageTupleIndexBuilding(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, KEY_SCHEMA_ID, shape));
    requireOk(session.commit(outcome));

    requireOk(session.begin(IsolationLevel.SERIALIZABLE));
    requireOk(session.preflightTupleIndexLifecycles(1));
    requireOk(session.stageTupleIndexReady(
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, KEY_SCHEMA_ID, shape));
    ByteBuffer tuple = physicalFixedTuple(1, 771);
    requireOk(session.preflightTupleMutations(1, 1, tuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT, OWNER_OBJECT_ID, 1_000,
        KEY_SCHEMA_ID, shape, 1, tuple, 0, tuple.remaining()));
    long baseSpace = CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID);
    requireOk(session.insert(baseSpace, 1, scalarRow(771)));
    requireOk(session.commit(outcome));
    check(outcome.state() == TransactionState.COMMITTED,
        "combined root publication and tuple DML did not commit");
    assertRecoveredRegistry(created.store(), 1_000, 4, OWNER_OBJECT_ID, 2, KEY_SCHEMA_ID);
    HeapRowResult row = new HeapRowResult();
    requireOk(created.store().fetchByKey(baseSpace, 1, row));
    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void checkpointValidationRejectsTupleOwnershipAndGraphFaults(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult table = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), table));
    requireOk(created.store().close());
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    long hash = descriptorHash(descriptor);
    appendRootGroup(wal, descriptor, hash, 2, 0, 4, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_BUILDING, 0, 2, 4, 5);
    appendRootGroup(wal, descriptor, hash, 3, 4, 4, 1, 2,
        IndexedRelationalSuboperations.REGISTRY_BUILDING,
        IndexedRelationalSuboperations.REGISTRY_READY, 2, 0, 5, 5);
    appendTupleInsertGroup(wal, descriptor, hash);
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    IndexedTableStore store = reopened.store();
    IndexedPageSet pages = pageSet(store);
    checkValidationAllocationFailures(store, pages);
    int next = BTreeRootPage.nextPageId(
        pages.currentPayloadUnchecked(IndexedTableKernel.ROOT_META_PAGE_ID));
    checkDuplicateReachability(pages, next);

    ByteBuffer tupleRoot = pages.currentPayloadUnchecked(4);
    int pointer = tupleRoot.getInt(24);
    tupleRoot.putInt(24, 4);
    check(store.validate() == StatusCode.CORRUPTION, "tuple leaf cycle accepted");
    tupleRoot.putInt(24, pointer);

    ByteBuffer metadata = pages.currentPayloadUnchecked(IndexedTableKernel.ROOT_META_PAGE_ID);
    int orphan = BTreeRootPage.nextAllocationPage(metadata);
    requireOk(BTreeRootPage.allocatePage(metadata, orphan, -1));
    ByteBuffer orphanPage = pages.stageNew(
        orphan, IndexedTableLimits.MAX_CHANGED_PAGES,
        PageCodec.PAYLOAD_KIND_TUPLE_BTREE, 1_000);
    TupleShape shape = shape(descriptor);
    requireOk(TupleBTreePageCodec.initialize(
        orphanPage, 0, TupleBTreePageCodec.TYPE_LEAF, 0,
        shape, 1_000, null, 0, 0));
    requireOk(pages.beginPreparedBatch());
    requireOk(pages.freezeChangedPages(0, Long.MAX_VALUE));
    requireOk(pages.installPreparedPages(
        new long[] {store.lastCommitSequence + 1}, 1, 1, 2));
    requireOk(pages.releasePreparedBatch());
    pages.resetChanges();
    check(store.validate() == StatusCode.CORRUPTION, "orphan tuple page accepted");
    check(store.flush() == StatusCode.CORRUPTION,
        "checkpoint writer admitted orphan tuple page");
    store.closeOpenFile();
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void checkpointReopenAcceptsPopulatedSplitBuildingRegistryHead(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult table = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), table));
    requireOk(created.store().close());
    int[] descriptor = {
        SqlTypeDescriptor.varchar(255),
        SqlTypeDescriptor.varchar(255),
        SqlTypeDescriptor.varchar(250)
    };
    appendRootGroup(wal, descriptor, descriptorHash(descriptor), 2, 0, 4, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_BUILDING, 0, 2, 4, 5);
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    int tuples = 80;
    long prediction = predictTupleInsert(
        reopened.store(), descriptor, 4, 1, tuples, 'x');
    int tupleRoot = (int) (prediction >>> 32);
    int nextPage = (int) prediction;
    check(tupleRoot != 4 && nextPage - 4 <= 50,
        "fixed tuple fixture root/next " + tupleRoot + "/" + nextPage);
    IndexedRelationalMutation mutation =
        new IndexedRelationalMutation(tuples, 1, descriptor.length);
    requireOk(mutation.reserve(
        tuples, 1, descriptor.length, tuples * 3_080));
    long hash = descriptorHash(descriptor);
    requireOk(mutation.appendDescriptor(
        OWNER_OBJECT_ID, 1_000, 1_000, hash, descriptor, 0, descriptor.length));
    requireOk(mutation.appendSuboperation(
        OWNER_OBJECT_ID, 0, 0, tuples, 4, tupleRoot,
        SCALAR_ROOT, SCALAR_ROOT, 5, nextPage,
        1, 2, 1, 2, IndexedRelationalMutation.REGISTRY_BUILDING,
        IndexedRelationalMutation.REGISTRY_BUILDING, 2, 2));
    for (int index = 0; index < tuples; index++) {
      ByteBuffer tuple = physicalTuple(descriptor, index + 1L, 'x');
      requireOk(mutation.appendTuple(
          0, OWNER_OBJECT_ID, IndexedRelationalMutation.TUPLE_INSERT,
          0, index + 1L, tuple, 0, tuple.remaining()));
    }
    requireOk(mutation.seal());
    requireOk(commitRelationalQuiescent(reopened.store(), 
        TRANSACTION_ID + 100, mutation, new IndexedCommitResult()));
    check(pageSet(reopened.store()).payloadKind(tupleRoot)
        == PageCodec.PAYLOAD_KIND_TUPLE_BTREE, "BUILDING tuple tree did not split");
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_BUILDING, tupleRoot, 2, 2);
    requireOk(commitRelationalQuiescent(reopened.store(), 
        TRANSACTION_ID + 101, liveRootMutation(
            descriptor, hash, tupleRoot, 0, 2, 3, 2, 3,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_DROPPING, 2, 2, nextPage, nextPage),
        new IndexedCommitResult()));
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_DROPPING, 0, 3, 2);
    long transaction = TRANSACTION_ID + 102;
    long generation = 3;
    long heapVersion = 3;
    int cleanupCursor = BTreeRootPage.FIRST_REUSABLE_PAGE_ID;
    while (cleanupCursor < nextPage) {
      int resultingCursor = Math.min(
          nextPage, cleanupCursor + IndexedTupleGraphReclaimer.MAX_INSPECTED_PAGES);
      requireOk(commitRelationalQuiescent(reopened.store(), 
          transaction++, liveRootMutation(
              descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
              0, 0, generation, generation + 1, heapVersion, heapVersion + 1,
              IndexedRelationalMutation.REGISTRY_DROPPING,
              IndexedRelationalMutation.REGISTRY_DROPPING, 2, 2,
              nextPage, nextPage, cleanupCursor, resultingCursor),
          new IndexedCommitResult()));
      generation++;
      heapVersion++;
      cleanupCursor = resultingCursor;
    }
    check(BTreeRootPage.freePageCount(
        pageSet(reopened.store()).currentPayloadUnchecked(2)) == nextPage - 4,
        "failed BUILDING graph was not reclaimed");
    requireOk(commitRelationalQuiescent(reopened.store(), 
        transaction, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
            0, 0, generation, generation + 1, heapVersion, heapVersion + 1,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            IndexedRelationalMutation.REGISTRY_ABSENT, 2, 0,
            nextPage, nextPage, cleanupCursor, 0),
        new IndexedCommitResult()));
    generation++;
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_ABSENT, 0, generation, 0);
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void checkpointReopenAcceptsEmptyBuildingRegistryHead(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult table = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), table));
    requireOk(created.store().close());
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    appendRootGroup(wal, descriptor, descriptorHash(descriptor), 2, 0, 0, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_BUILDING, 0, 2, 4, 4);
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_BUILDING, 0, 1, 2);
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    requireOk(commitRelationalQuiescent(reopened.store(), 
        TRANSACTION_ID + 102, liveRootMutation(
            descriptor, descriptorHash(descriptor), 0, 0, 1, 2, 1, 2,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_ABSENT, 2, 0, 4, 4),
        new IndexedCommitResult()));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_ABSENT, 0, 2, 0);
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void checkpointReopenAcceptsDroppingRegistryGraph(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult table = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), table));
    requireOk(created.store().close());
    int[] descriptor = {SqlTypeDescriptor.BIGINT};
    long hash = descriptorHash(descriptor);
    appendRootGroup(wal, descriptor, hash, 2, 0, 4, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_BUILDING, 0, 2, 4, 5);
    appendRootGroup(wal, descriptor, hash, 3, 4, 4, 1, 2,
        IndexedRelationalSuboperations.REGISTRY_BUILDING,
        IndexedRelationalSuboperations.REGISTRY_READY, 2, 0, 5, 5);
    appendRootGroup(wal, descriptor, hash, 4, 4, 4, 2, 3,
        IndexedRelationalSuboperations.REGISTRY_READY,
        IndexedRelationalSuboperations.REGISTRY_DROPPING, 0, 4, 5, 5);
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_DROPPING, 4, 3, 4);
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    assertRecoveredRegistryState(
        reopened.store(), TupleIndexRootRecordCodec.STATE_DROPPING, 4, 3, 4);
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  @Test
  void detachedDroppingReclaimsAcrossBatchesAndReusesAfterReopen(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory, false);
    IndexedTableStoreOpenResult created = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.create(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), created));
    IndexedTableOpenResult opened = new IndexedTableOpenResult();
    requireOk(IndexedTable.create(created.store(), opened));
    int[] descriptor = {
        SqlTypeDescriptor.varchar(255),
        SqlTypeDescriptor.varchar(255),
        SqlTypeDescriptor.varchar(250)
    };
    long hash = descriptorHash(descriptor);
    long transaction = TRANSACTION_ID + 110;
    long generation = 1;
    long heap = 1;
    requireOk(commitRelationalQuiescent(created.store(), 
        transaction++, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000,
            0, 0, 0, generation, 0, heap,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING, 0, 2, 4, 4),
        new IndexedCommitResult()));
    int tupleRoot = 0;
    int nextPage = 4;
    long logicalRowId = 1;
    for (int batch = 0; nextPage - 4 <= IndexedTableLimits.MAX_CHANGED_PAGES; batch++) {
      check(batch < 8, "tuple graph did not cross the changed-page bound");
      int tupleCount = 80;
      char value = (char) ('a' + batch);
      long prediction = predictTupleInsert(
          created.store(), descriptor, tupleRoot, logicalRowId, tupleCount, value);
      int resultingRoot = (int) (prediction >>> 32);
      int resultingNext = (int) prediction;
      requireOk(commitRelationalQuiescent(created.store(), 
          transaction++, liveTupleInsertMutation(
              descriptor, hash, tupleRoot, resultingRoot, nextPage, resultingNext,
              generation, heap, logicalRowId, tupleCount, value),
          new IndexedCommitResult()));
      tupleRoot = resultingRoot;
      nextPage = resultingNext;
      generation++;
      heap++;
      logicalRowId += tupleCount;
    }
    int ownedPages = nextPage - BTreeRootPage.FIRST_REUSABLE_PAGE_ID;
    requireOk(commitRelationalQuiescent(created.store(), 
        transaction++, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000,
            tupleRoot, 0, generation, generation + 1, heap, heap + 1,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            IndexedRelationalMutation.REGISTRY_DROPPING, 2, 2,
            nextPage, nextPage), new IndexedCommitResult()));
    generation++;
    heap++;
    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    IndexedTableStoreOpenResult reopened = null;
    int freePages = 0;
    int cleanupCursor = BTreeRootPage.FIRST_REUSABLE_PAGE_ID;
    while (cleanupCursor < nextPage) {
      directory = openDirectory(root);
      wal = openWal(directory, true);
      reopened = new IndexedTableStoreOpenResult();
      requireOk(IndexedTableStore.openExisting(
          directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
      int before = freePages;
      int resultingCursor = Math.min(
          nextPage, cleanupCursor + IndexedTupleGraphReclaimer.MAX_INSPECTED_PAGES);
      requireOk(commitRelationalQuiescent(reopened.store(), 
          transaction++, liveRootMutation(
              descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
              0, 0, generation, generation + 1, heap, heap + 1,
              IndexedRelationalMutation.REGISTRY_DROPPING,
              IndexedRelationalMutation.REGISTRY_DROPPING, 2, 2,
              nextPage, nextPage, cleanupCursor, resultingCursor),
          new IndexedCommitResult()));
      generation++;
      heap++;
      cleanupCursor = resultingCursor;
      freePages = BTreeRootPage.freePageCount(
          pageSet(reopened.store()).currentPayloadUnchecked(2));
      check(freePages > before
              && freePages - before <= IndexedTupleGraphReclaimer.MAX_INSPECTED_PAGES,
          "detached cleanup batch was not bounded and progressive");
      requireOk(reopened.store().flush());
      requireOk(reopened.store().close());
      requireOk(wal.close());
      requireOk(directory.close());
    }

    directory = openDirectory(root);
    wal = openWal(directory, true);
    reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    requireOk(commitRelationalQuiescent(reopened.store(), 
        transaction++, liveRootMutation(
            descriptor, hash, OWNER_OBJECT_ID, 1_000, 1_000,
            0, 0, generation, generation + 1, heap, heap + 1,
            IndexedRelationalMutation.REGISTRY_DROPPING,
            IndexedRelationalMutation.REGISTRY_ABSENT, 2, 0,
            nextPage, nextPage, cleanupCursor, 0), new IndexedCommitResult()));
    generation++;
    heap++;
    int[] second = {SqlTypeDescriptor.varchar(16)};
    long secondHash = descriptorHash(second);
    int reusedRoot = BTreeRootPage.freePageHead(
        pageSet(reopened.store()).currentPayloadUnchecked(2));
    requireOk(commitRelationalQuiescent(reopened.store(), 
        transaction, liveRootMutation(
            second, secondHash, SECOND_OWNER_OBJECT_ID, 1_001,
            0, reusedRoot, 0, 1, heap, heap + 1,
            IndexedRelationalMutation.REGISTRY_ABSENT,
            IndexedRelationalMutation.REGISTRY_BUILDING,
            0, transaction, nextPage, nextPage), new IndexedCommitResult()));
    IndexedPageSet reused = pageSet(reopened.store());
    check(reused.payloadKind(reusedRoot) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
        && reused.ownerKeyId(reusedRoot) == 1_001
        && BTreeRootPage.freePageCount(reused.currentPayloadUnchecked(2))
            == ownedPages - 1,
        "detached free page was aliased or not reused LIFO");
    requireOk(reopened.store().flush());
    requireOk(reopened.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }

  private static StatusCode decodeOne(ByteBuffer source) {
    IndexedRelationalMutationBuffer output =
        new IndexedRelationalMutationBuffer(1, 0, 0);
    return new IndexedRelationalWalDecoder(output).decode(source, TRANSACTION_ID, 1);
  }

  private static IndexedRelationalMutation liveBaseMutation(int expectedRoot, long value) {
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

  private static IndexedRelationalMutation liveTupleInsertMutation(
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

  private static long predictTupleInsert(
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

  private static int tupleInsertNewPageCount(
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

  private static int tuplePageCount(IndexedTableStore store, long keyId) throws Exception {
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

  private static IndexedRelationalMutation liveRootMutation(
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

  private static IndexedRelationalMutation liveRootMutation(
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

  private static IndexedRelationalMutation liveRootMutation(
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

  private static IndexedRelationalMutation liveRootMutation(
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

  private static ByteBuffer encode(IndexedRelationalWalPlan plan, int chunk) {
    ByteBuffer result = ByteBuffer.allocate(plan.payloadBytesAt(chunk));
    requireOk(IndexedRelationalWalCodec.encode(plan, chunk, result));
    result.flip();
    return result;
  }

  private static IndexedRelationalWalPlan oneBasePlan(
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

  private static IndexedRelationalWalPlan descriptorOnlyPlan(
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

  private static LocalWalReadResult record(
      IndexedRelationalWalPlan plan, int chunk,
      long commitSequence, int decision, long offset) {
    ByteBuffer payload = encode(plan, chunk);
    LocalWalReadResult result = new LocalWalReadResult();
    result.header().set(
        payload.remaining(), payload.remaining(),
        IndexedRelationalWalCodec.WAL_FORMAT_ID,
        IndexedRelationalWalCodec.WAL_FORMAT_VERSION,
        chunk + 1L, TRANSACTION_ID, commitSequence, decision);
    result.set(offset + payload.remaining(), payload);
    return result;
  }

  private static void appendRootGroup(
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

  private static void appendRootGroup(
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

  private static void appendRootGroup(
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

  private static void appendTupleInsertGroup(LocalWal wal, int[] descriptor, long hash) {
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

  private static void appendTupleInsertGroup(
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

  private static void appendBaseInsertGroup(LocalWal wal, long sequence, long expectedHeap) {
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

  private static void appendIncompleteBaseGroup(LocalWal wal) {
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

  private static void commitGroup(
      LocalWal wal, IndexedRelationalMutationBuffer mutations, long sequence) {
    requireOk(mutations.seal());
    IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
    requireOk(plan.plan(sequence, OPERATION_ID + sequence, mutations));
    IndexedRelationalWalCommitter committer =
        new IndexedRelationalWalCommitter(wal, new IndexedGroupCommitMetrics());
    requireOk(committer.appendAndForce(plan, sequence));
    requireOk(committer.releaseForced());
  }

  private static StatusCode commitRelationalQuiescent(
      IndexedTableStore store,
      long transactionId,
      IndexedRelationalMutation mutation,
      IndexedCommitResult result) {
    return store.commitRelational(
        transactionId, mutation, Long.MAX_VALUE, result);
  }

  private static ByteBuffer physicalFixedTuple(long logicalRowId, long value) {
    ByteBuffer result = ByteBuffer.allocate(32);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    requireOk(builder.beginIndex(result, 0, 1));
    requireOk(builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    requireOk(builder.finishPhysical(logicalRowId));
    result.position(0);
    result.limit(builder.keyBytes());
    return result;
  }

  private static ByteBuffer genericFixedTuple(long value) {
    ByteBuffer result = ByteBuffer.allocate(24);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    requireOk(builder.beginTuple(result, 0, 1));
    requireOk(builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    requireOk(builder.finishTuple());
    result.position(0);
    result.limit(builder.keyBytes());
    return result;
  }

  private static ByteBuffer physicalTextTuple(long logicalRowId, String value) {
    ByteBuffer result = ByteBuffer.allocate(64);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    requireOk(builder.beginIndex(result, 0, 1));
    requireOk(builder.addText(SqlTypeDescriptor.varchar(16), value));
    requireOk(builder.finishPhysical(logicalRowId));
    result.position(0);
    result.limit(builder.keyBytes());
    return result;
  }

  private static ByteBuffer scalarRow(long value) {
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    row.putLong(0, value);
    return row;
  }

  private static TupleIndexRootRecord registryRecord(
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

  private static void assertReadyRegistry(
      IndexedTableStore store, long keyId, long owner, int rootPageId,
      long generation) {
    TupleIndexRootRecord record = registryRecord(store, keyId);
    check(record.state() == TupleIndexRootRecordCodec.STATE_READY
        && record.rootPageId() == rootPageId && record.ownerObjectId() == owner
        && record.schemaId() == KEY_SCHEMA_ID && record.generation() == generation
        && record.privateOwner() == 0 && record.cleanupCursor() == 0,
        "batched READY registry mismatch");
  }

  private static void assertAbsentRegistry(
      IndexedTableStore store, long keyId, long owner, long generation) {
    TupleIndexRootRecord record = registryRecord(store, keyId);
    check(record.state() == TupleIndexRootRecordCodec.STATE_ABSENT
        && record.rootPageId() == 0 && record.ownerObjectId() == owner
        && record.schemaId() == KEY_SCHEMA_ID && record.generation() == generation
        && record.privateOwner() == 0 && record.cleanupCursor() == 0,
        "batched ABSENT registry mismatch");
  }

  private static void assertRecoveredRegistry(IndexedTableStore store) {
    assertRecoveredRegistry(store, 1_000, 4, OWNER_OBJECT_ID);
  }

  private static void assertRecoveredRegistry(
      IndexedTableStore store, long keyId, int rootPageId, long ownerObjectId) {
    assertRecoveredRegistry(store, keyId, rootPageId, ownerObjectId, 3);
  }

  private static void assertRecoveredRegistry(
      IndexedTableStore store, long keyId, int rootPageId,
      long ownerObjectId, long generation) {
    assertRecoveredRegistry(
        store, keyId, rootPageId, ownerObjectId, generation, keyId);
  }

  private static void assertRecoveredRegistry(
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

  private static void assertRecoveredRegistryState(
      IndexedTableStore store, int state, int rootPageId,
      long generation, long privateOwner) {
    int cleanupCursor = state == TupleIndexRootRecordCodec.STATE_DROPPING
        && rootPageId == 0 ? BTreeRootPage.FIRST_REUSABLE_PAGE_ID : 0;
    assertRecoveredRegistryState(
        store, state, rootPageId, generation, privateOwner, cleanupCursor);
  }

  private static void assertRecoveredRegistryState(
      IndexedTableStore store, int state, int rootPageId,
      long generation, long privateOwner, int cleanupCursor) {
    HeapRowResult row = new HeapRowResult();
    requireOk(store.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, 1_000, row));
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    requireOk(row.copyTo(bytes));
    bytes.flip();
    TupleIndexRootRecord record = new TupleIndexRootRecord();
    requireOk(TupleIndexRootRecordCodec.decode(bytes, 0, record, new CRC32C()));
    check(record.state() == state && record.rootPageId() == rootPageId
        && record.generation() == generation && record.privateOwner() == privateOwner
        && record.cleanupCursor() == cleanupCursor,
        "registry lifecycle replay mismatch");
  }

  private static void assertRecoveredFreeChain(IndexedPageSet pages, int count) {
    ByteBuffer metadata = pages.currentPayloadUnchecked(IndexedTableKernel.ROOT_META_PAGE_ID);
    check(BTreeRootPage.freePageCount(metadata) == count,
        "WAL-only cleanup recovered the wrong free-page count");
    int pageId = BTreeRootPage.freePageHead(metadata);
    for (int expected = BTreeRootPage.FIRST_REUSABLE_PAGE_ID + count - 1;
        expected >= BTreeRootPage.FIRST_REUSABLE_PAGE_ID; expected--) {
      check(pageId == expected && pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_FREE
              && pages.ownerKeyId(pageId) == 0,
          "WAL-only cleanup recovered a wrong free-page identity");
      pageId = BTreeFreePage.nextPageId(pages.currentPayloadUnchecked(pageId));
    }
    check(pageId == 0, "WAL-only cleanup recovered a trailing free-page link");
  }

  private static NioDurableDirectory openDirectory(Path root) {
    return openDirectory(root, new NioIoCounters());
  }

  private static NioDurableDirectory openDirectory(Path root, NioIoCounters counters) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    requireOk(NioDurableDirectory.openExisting(
        root, new FatalStateFence(), counters, 8, result));
    return result.directory();
  }

  private static void prepareHybrid(
      IndexedTransactionSession session, int[] descriptor,
      long baseSpace, int key, long value) {
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    requireOk(session.insert(baseSpace, key, scalarRow(value)));
    ByteBuffer tuple = physicalFixedTuple(key, value);
    requireOk(session.preflightTupleMutations(1, 1, tuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), key,
        tuple, tuple.position(), tuple.remaining()));
  }

  private static StatusCode coordinatedCommit(
      IndexedTransactionSession session, TransactionOutcome outcome,
      CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await();
    return session.commit(outcome);
  }

  private static void assertTuple(
      IndexedTableStore store, int[] descriptor, long value, long logicalRowId) {
    ByteBuffer key = genericFixedTuple(value);
    IndexedTupleProbeResult probe = new IndexedTupleProbeResult();
    requireOk(store.probeTuplePrefixAt(
        store.currentCommitSequence(), OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID,
        shape(descriptor), key, 0, key.remaining(), probe));
    check(probe.found() && probe.logicalRowId() == logicalRowId,
        "grouped hybrid tuple missing for row " + logicalRowId);
  }

  private static void assertBaseRow(
      IndexedTableStore store, long space, long key, long expectedValue) {
    HeapRowResult row = new HeapRowResult();
    requireOk(store.fetchByKey(space, key, row));
    check(row.getLong(0) == expectedValue, "hybrid base row value mismatch for " + key);
  }

  private static LocalWal openWal(NioDurableDirectory directory, boolean existing) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    requireOk(LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static void crashWal(LocalWal wal) throws Exception {
    Field file = LocalWal.class.getDeclaredField("file");
    file.setAccessible(true);
    requireOk(((io.riverdb.platform.file.DurableFile) file.get(wal)).close());
  }

  private static void appendBaseSuboperations(
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

  private static long descriptorHash(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    requireOk(TupleShape.create(descriptors, result));
    return result.value().descriptorHash();
  }

  private static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    requireOk(TupleShape.create(descriptors, result));
    return result.value();
  }

  private static void checkDuplicateReachability(IndexedPageSet pages, int nextPageId) {
    IndexedTupleValidationProvider provider = new IndexedTupleValidationProvider(pages);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    requireOk(provider.configure(4, 1_000, nextPageId));
    requireOk(provider.visit(4));
    requireOk(provider.pin(4, false, reference));
    requireOk(provider.release(reference));
    reference.reset();
    requireOk(provider.configure(4, 1_000, nextPageId));
    check(provider.visit(4) == StatusCode.CORRUPTION,
        "tuple page reached twice across graphs");
  }

  private static IndexedPageSet pageSet(IndexedTableStore store) throws Exception {
    Field field = IndexedTableStore.class.getDeclaredField("pages");
    field.setAccessible(true);
    return (IndexedPageSet) field.get(store);
  }

  private static void checkValidationAllocationFailures(
      IndexedTableStore store, IndexedPageSet pages) throws Exception {
    FailingPagedAllocator scalarFailure = new FailingPagedAllocator();
    replaceValidator(
        store,
        pages,
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES, scalarFailure),
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES));
    check(store.flush() == StatusCode.RESOURCE_EXHAUSTED,
        "scalar visitation allocation failure escaped flush");
    scalarFailure.allowAllocations();
    requireOk(store.flush());

    FailingPagedAllocator tupleFailure = new FailingPagedAllocator();
    replaceValidator(
        store,
        pages,
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES),
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES, tupleFailure));
    check(store.flush() == StatusCode.RESOURCE_EXHAUSTED,
        "tuple visitation allocation failure escaped flush");
    tupleFailure.allowAllocations();
    requireOk(store.flush());
  }

  private static void replaceValidator(
      IndexedTableStore store,
      IndexedPageSet pages,
      PagedBooleanArray scalarVisited,
      PagedBooleanArray tupleVisited) throws Exception {
    Field kernelField = IndexedTableStore.class.getDeclaredField("kernel");
    kernelField.setAccessible(true);
    IndexedTableKernel kernel = (IndexedTableKernel) kernelField.get(store);
    Field componentsField = IndexedTableKernel.class.getDeclaredField("components");
    componentsField.setAccessible(true);
    IndexedKernelComponents components =
        (IndexedKernelComponents) componentsField.get(kernel);
    Field validatorField = IndexedKernelComponents.class.getDeclaredField("validator");
    validatorField.setAccessible(true);
    validatorField.set(
        components,
        new IndexedTableValidator(
            pages, kernel.versionState(), scalarVisited, tupleVisited));
  }

  private static ByteBuffer physicalTuple(int[] descriptors, long logicalRowId, char value) {
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

  private static String repeated(char value, int count) {
    char[] characters = new char[count];
    for (int index = 0; index < count; index++) characters[index] = value;
    return new String(characters);
  }

  private static IndexedSessionContext context(
      TransactionManager manager,
      IndexedTable table,
      IndexedGroupCommitCoordinator coordinator,
      IndexedVacuum vacuum) {
    IndexedSessionContext.Result result = new IndexedSessionContext.Result();
    requireOk(IndexedSessionContext.bind(manager, table, coordinator, vacuum, result));
    return result.context();
  }

  private static IndexedTransactionSession session(
      IndexedSessionContext context, int maximumRowBytes) {
    IndexedTransactionSessionOpenResult result =
        new IndexedTransactionSessionOpenResult();
    requireOk(context.openSession(maximumRowBytes, result));
    return result.session();
  }

  private static void requireOk(StatusCode status) {
    if (!status.isOk()) throw new AssertionError("expected OK, got " + status);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class PrefixBatch implements LocalWalRecordBatch {
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

  private static final class RecordingReplay implements IndexedRelationalWalReplay {
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

  private static final class FailingPagedAllocator implements IndexedPagedArrayAllocator {
    private boolean failing = true;
    private void allowAllocations() { failing = false; }
    @Override public byte[] allocateBytes(int size) {
      if (failing) throw new OutOfMemoryError("injected");
      return new byte[size];
    }
    @Override public int[] allocateInts(int size) { return new int[size]; }
    @Override public long[] allocateLongs(int size) { return new long[size]; }
  }
}
