package io.riverdb.engine.table;

import static io.riverdb.engine.table.IndexedRelationalWalMutationFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalRecordFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalRegistryFixtures.*;
import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.wal.local.LocalWal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/** Tests for relational WAL checkpoint scenarios. */
final class IndexedRelationalWalCheckpointTest {
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
    check(store.kernel.validate() == StatusCode.CORRUPTION, "tuple leaf cycle accepted");
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
    check(store.kernel.validate() == StatusCode.CORRUPTION, "orphan tuple page accepted");
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
}
