package io.riverdb.engine.table;

import static io.riverdb.engine.table.IndexedRelationalWalMutationFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalRecordFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalRegistryFixtures.*;
import static io.riverdb.engine.TestDatabaseResources.databasePlan;
import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static io.riverdb.engine.TestDatabaseResources.runtimeRoot;
import static io.riverdb.tx.TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalForceTarget;
import io.riverdb.wal.local.LocalWalGroupAppendResult;
import io.riverdb.wal.local.LocalWalLogicalStream;
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.riverdb.engine.table.IndexedRelationalWalRecordFixtures.PrefixBatch;
import io.riverdb.engine.table.IndexedRelationalWalRecordFixtures.RecordingReplay;


/** Tests for relational WAL recovery scenarios. */
final class IndexedRelationalWalRecoveryTest {
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
    interleaved.set(2_000, 2_000, ByteBuffer.allocate(0));
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
    LocalWalForceTarget target = new LocalWalForceTarget();
    requireOk(wal.forceLogicalStreamBatch(stream, target));
    requireOk(wal.releaseLogicalStreamBatch(stream, target, target.token()));
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
    LocalWalForceTarget target = new LocalWalForceTarget();
    requireOk(primary.forceLogicalStreamBatch(stream, target));
    requireOk(primary.releaseLogicalStreamBatch(stream, target, target.token()));
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
}
