package io.riverdb.engine.table;

import static io.riverdb.engine.table.IndexedRelationalWalMutationFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalRegistryFixtures.*;
import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/** Tests for relational WAL commit scenarios. */
final class IndexedRelationalWalCommitTest {
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
    check(created.store().kernel.rowCount() == 0 && created.store().currentCommitSequence() == 1,
        "failed live group changed current state");

    IndexedRelationalMutation valid = liveBaseMutation(SCALAR_ROOT, 811);
    requireOk(commitRelationalQuiescent(created.store(), TRANSACTION_ID, valid, commit));
    check(commit.commitSequence() == 2 && created.store().kernel.rowCount() == 1,
        "live grouped commit did not publish one frontier");
    HeapRowResult row = new HeapRowResult();
    long space = CatalogKeyspace.relationalBaseRowSpace(OWNER_OBJECT_ID);
    requireOk(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence, space, 1, row));
    check(row.getLong(0) == 811, "live grouped base row mismatch");
    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());

    directory = openDirectory(root);
    wal = openWal(directory, true);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    requireOk(IndexedTableStore.openExisting(directory, wal, DATABASE, GENERATION, databaseProviderLease(4), reopened));
    requireOk(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, space, 1, row));
    check(row.getLong(0) == 811 && reopened.store().kernel.rowCount() == 1,
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
    check(commit.commitSequence() == 4 && created.store().kernel.rowCount() == 4,
        "base-and-tuple group did not publish one frontier");
    assertRecoveredRegistry(
        created.store(), 1_000, 4, OWNER_OBJECT_ID, 3, KEY_SCHEMA_ID);
    HeapRowResult fetched = new HeapRowResult();
    requireOk(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence,
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
    check(reopened.store().kernel.rowCount() == 10, "atomic grouped recovery frontier mismatch");
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
    requireOk(session.protectTupleKeyForWrite(1_000, tuple, tuple.position(), tuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), 1,
        tuple, tuple.position(), tuple.remaining()));
    TransactionOutcome outcome = new TransactionOutcome();
    requireOk(session.commit(outcome));

    HeapRowResult fetched = new HeapRowResult();
    requireOk(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence, baseSpace, 1, fetched));
    check(fetched.getLong(0) == 991, "hybrid base row missing");
    assertRecoveredRegistry(
        created.store(), 1_000, 4, OWNER_OBJECT_ID, 3, KEY_SCHEMA_ID);
    check(created.store().kernel.rowCount() == 4,
        "hybrid base and registry did not publish one heap frontier");
    IndexedSavepoint savepoint = new IndexedSavepoint();
    requireOk(session.begin(IsolationLevel.REPEATABLE_READ));
    requireOk(session.createSavepoint(savepoint));
    ByteBuffer secondRow = ByteBuffer.allocate(Long.BYTES);
    secondRow.putLong(0, 992);
    requireOk(session.insert(baseSpace, 2, secondRow));
    ByteBuffer secondTuple = physicalFixedTuple(2, 992);
    requireOk(session.preflightTupleMutations(1, 1, secondTuple.remaining()));
    requireOk(session.protectTupleKeyForWrite(
        1_000, secondTuple, secondTuple.position(), secondTuple.remaining()));
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
    requireOk(session.protectTupleKeyForWrite(
        1_000, secondTuple, secondTuple.position(), secondTuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), 2,
        secondTuple, secondTuple.position(), secondTuple.remaining()));
    requireOk(session.abort(outcome));
    check(outcome.state() == TransactionState.ABORTED,
        "hybrid abort did not report ABORTED");
    check(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence, baseSpace, 2, fetched) == StatusCode.CONFLICT,
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
    requireOk(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, baseSpace, 1, fetched));
    check(fetched.getLong(0) == 991, "reopen lost hybrid base row");
    check(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, baseSpace, 2, fetched) == StatusCode.CONFLICT,
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
    requireOk(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence, baseSpace, 1, fetched));
    check(fetched.getLong(0) == 991, "first grouped hybrid row missing");
    requireOk(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence, baseSpace, 2, fetched));
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
    requireOk(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, baseSpace, 1, fetched));
    requireOk(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, baseSpace, 2, fetched));
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
    requireOk(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, baseSpace, 1, fetched));
    requireOk(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, baseSpace, 2, fetched));
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
    check(batch.appendSharedGroup(2),
        "split cohort failed before prepared publication");
    check(counters.forceCalls() == forceCalls,
        "split cohort forced before handing off locks");
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
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_FORCE) == 0,
        "split cohort forced before publication");
    check(forcedTelemetry.stageCount(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_PUBLICATION) == 0,
        "split cohort published before the explicit publication phase");

    check(batch.publishPrepared(2), "split cohort failed prepared publication");
    check(first.groupTransaction().state() == TransactionState.COMMITTING
            && second.groupTransaction().state() == TransactionState.COMMITTING,
        "published cohort acknowledged before durability");
    batch.completeDurability(2);
    check(counters.forceCalls() == forceCalls + 1,
        "split cohort did not use exactly one shared force");
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
    check(publishedTelemetry.groupLockHoldingsReleased() > 0,
        "split cohort released no measured lock holdings");
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
    requireOk(session.protectTupleKeyForWrite(1_000, tuple, tuple.position(), tuple.remaining()));
    requireOk(session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        OWNER_OBJECT_ID, 1_000, KEY_SCHEMA_ID, shape(descriptor), 3,
        tuple, tuple.position(), tuple.remaining()));
    requireOk(session.createSavepoint(savepoint));
    requireOk(session.delete(baseSpace, 3));
    requireOk(session.preflightTupleMutations(1, 0, tuple.remaining()));
    requireOk(session.protectTupleKeyForWrite(1_000, tuple, tuple.position(), tuple.remaining()));
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
    requireOk(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence, 77, 1, scalar));
    requireOk(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence, 77, 2, scalar));

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
    requireOk(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, 77, 1, scalar));
    requireOk(reopened.store().kernel.fetchByKeyAt(
        reopened.store().lastCommitSequence, 77, 2, scalar));

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
    requireOk(session.protectTupleKeyForWrite(1_000, tuple, tuple.position(), tuple.remaining()));
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
    requireOk(created.store().kernel.fetchByKeyAt(
        created.store().lastCommitSequence, baseSpace, 1, row));
    requireOk(created.store().flush());
    requireOk(created.store().close());
    requireOk(wal.close());
    requireOk(directory.close());
  }
}
