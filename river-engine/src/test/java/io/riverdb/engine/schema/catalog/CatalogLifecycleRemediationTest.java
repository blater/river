package io.riverdb.engine.schema.catalog;

import static io.riverdb.engine.TestDatabaseResources.databasePlan;
import static io.riverdb.engine.TestDatabaseResources.runtimeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleIndexState;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogBuildIntentCodec;
import io.riverdb.format.catalog.CatalogAllocationWatermark;
import io.riverdb.format.catalog.CatalogAllocationWatermarkCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.catalog.CatalogObjectHead;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CatalogLifecycleRemediationTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x43415452454d4544L, 0x494154494f4e3031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void indexedCreatePublishesExactRootsAndReopens(@TempDir Path root) {
    Opened opened = create(root);
    SchemaPin created = new SchemaPin();
    assertEquals(StatusCode.OK, opened.lifecycle.create(
        CatalogIndexRootTableFixture.table(), created, new StatusDetail(64)));
    TableDescriptor table = created.descriptor();
    assertPublishedRoot(opened.database, table, table.primaryKey().keyId());
    assertPublishedRoot(opened.database, table, table.secondaryKeyAt(0).keyId());
    assertEquals(StatusCode.OK, created.release());
    assertEquals(StatusCode.OK, opened.database.close());

    EmbeddedDatabaseOpenResult database = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.openExisting(runtimeRoot(), databasePlan(6), root, DATABASE, GENERATION, 6, database));
    SchemaCache.Result cache = new SchemaCache.Result();
    assertEquals(StatusCode.OK,
        SchemaCache.createBudgeted(8_000_000, cache, new StatusDetail(64)));
    CatalogTableLifecycle lifecycle = new CatalogTableLifecycle(database.database(), cache.value());
    assertEquals(StatusCode.OK, lifecycle.validate());
    SchemaPin reopened = new SchemaPin();
    assertEquals(StatusCode.OK,
        lifecycle.open(table.tableId(), reopened, new StatusDetail(64)));
    assertPublishedRoot(database.database(), reopened.descriptor(),
        reopened.descriptor().primaryKey().keyId());
    assertPublishedRoot(database.database(), reopened.descriptor(),
        reopened.descriptor().secondaryKeyAt(0).keyId());
    assertEquals(StatusCode.OK, reopened.release());
    assertEquals(StatusCode.OK, database.database().close());
  }

  @Test
  void abortedIndexedCreateReclaimsPrivateRootsBeforeIntent(@TempDir Path root) {
    Opened opened = create(root);
    IndexedTransactionSession publication = session(opened.database);
    CatalogPreparedTable prepared = new CatalogPreparedTable();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, publication.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, opened.lifecycle.prepare(
        CatalogIndexRootTableFixture.table(), publication,
        prepared, new StatusDetail(64)));
    TableDescriptor table = prepared.descriptor();
    long objectId = prepared.objectId();
    long primaryKeyId = table.primaryKey().keyId();
    long secondaryKeyId = table.secondaryKeyAt(0).keyId();
    assertEquals(StatusCode.OK, publication.abort(outcome));
    assertEquals(StatusCode.OK,
        opened.lifecycle.finish(prepared, TransactionState.ABORTED));
    assertAbsentRoot(opened.database, primaryKeyId);
    assertAbsentRoot(opened.database, secondaryKeyId);
    assertIntentMissing(opened.database, objectId);
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void startupRejectsPublishedTableWithMissingTupleRoot(@TempDir Path root) {
    Opened opened = create(root);
    SchemaPin created = new SchemaPin();
    assertEquals(StatusCode.OK, opened.lifecycle.create(
        CatalogIndexRootTableFixture.table(), created, new StatusDetail(64)));
    long keyId = created.descriptor().primaryKey().keyId();
    assertEquals(StatusCode.OK, created.release());
    IndexedTransactionSession session = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.delete(CatalogKeyspace.INDEX_ROOT_SPACE, keyId));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.CORRUPTION, opened.lifecycle.validate());
    assertEquals(StatusCode.CORRUPTION, opened.database.close());
  }

  @Test
  void directCreateResetsReusedStatusDetailOnSuccessAndFailure(@TempDir Path root) {
    Opened opened = create(root);
    StatusDetail detail = new StatusDetail(64);
    SchemaPin pin = new SchemaPin();
    detail.set(StatusCode.CORRUPTION).append("stale");
    assertEquals(StatusCode.OK, opened.lifecycle.create(descriptor(), pin, detail));
    assertEquals(StatusCode.OK, detail.code());
    assertEquals(0, detail.length());
    assertEquals(StatusCode.OK, pin.release());
    detail.set(StatusCode.CORRUPTION).append("stale");
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        opened.lifecycle.create(null, pin, detail));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, detail.code());
    assertEquals(0, detail.length());
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void prepareRejectsForeignSessionsAndUnpublishedSuccessorPinsBeforeReservation(
      @TempDir Path root) throws java.io.IOException {
    Opened first = create(Files.createDirectory(root.resolve("first")));
    Opened second = create(Files.createDirectory(root.resolve("second")));
    IndexedTransactionSession foreign = session(second.database);
    TransactionOutcome outcome = new TransactionOutcome();
    CatalogPreparedTable rejected = new CatalogPreparedTable();
    CatalogAllocationWatermark before = allocationWatermark(first.database);
    StatusDetail detail = new StatusDetail(64);
    assertEquals(StatusCode.OK, foreign.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, first.lifecycle.prepare(
        descriptor(), foreign, rejected, detail));
    assertEquals(0, foreign.pendingMutationCount());
    assertEquals(StatusCode.OK, foreign.abort(outcome));
    assertSameWatermark(before, allocationWatermark(first.database));

    SchemaPin published = new SchemaPin();
    assertEquals(StatusCode.OK, first.lifecycle.create(descriptor(), published, detail));
    before = allocationWatermark(first.database);
    assertEquals(StatusCode.OK, foreign.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, first.lifecycle.prepareSuccessor(
        published, published.descriptor(), foreign, rejected, detail));
    assertEquals(0, foreign.pendingMutationCount());
    assertEquals(StatusCode.OK, foreign.abort(outcome));
    assertSameWatermark(before, allocationWatermark(first.database));

    IndexedTransactionSession creating = session(first.database);
    CatalogPreparedTable privateTable = new CatalogPreparedTable();
    assertEquals(StatusCode.OK, creating.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        first.lifecycle.prepare(descriptor(), creating, privateTable, detail));
    SchemaPin unpublished = new SchemaPin();
    assertEquals(StatusCode.OK, privateTable.borrow(unpublished));
    IndexedTransactionSession unrelated = session(first.database);
    assertEquals(StatusCode.OK, unrelated.begin(IsolationLevel.SERIALIZABLE));
    before = allocationWatermark(first.database);
    assertEquals(StatusCode.CONFLICT, first.lifecycle.prepareSuccessor(
        unpublished, unpublished.descriptor(), unrelated, rejected, detail));
    assertEquals(0, unrelated.pendingMutationCount());
    assertSameWatermark(before, allocationWatermark(first.database));
    assertEquals(StatusCode.OK, unrelated.abort(outcome));

    before = allocationWatermark(first.database);
    assertEquals(StatusCode.CONFLICT, first.lifecycle.prepareSuccessor(
        unpublished, unpublished.descriptor(), creating, rejected, detail));
    assertSameWatermark(before, allocationWatermark(first.database));
    assertEquals(StatusCode.OK, unpublished.release());
    assertEquals(StatusCode.OK, creating.abort(outcome));
    assertEquals(StatusCode.OK,
        first.lifecycle.finish(privateTable, TransactionState.ABORTED));
    assertEquals(StatusCode.OK, published.release());
    assertEquals(StatusCode.OK, first.database.close());
    assertEquals(StatusCode.OK, second.database.close());
  }

  @Test
  void prepareRejectsInactiveOwnedSessionBeforeDurableReservation(@TempDir Path root) {
    Opened opened = create(root);
    IndexedTransactionSession inactive = session(opened.database);
    CatalogPreparedTable prepared = new CatalogPreparedTable();
    StatusDetail detail = new StatusDetail(64);
    CatalogAllocationWatermark before = allocationWatermark(opened.database);
    assertEquals(StatusCode.CONFLICT,
        opened.lifecycle.prepare(descriptor(), inactive, prepared, detail));
    assertEquals(false, prepared.isActive());
    assertSameWatermark(before, allocationWatermark(opened.database));

    SchemaPin current = new SchemaPin();
    assertEquals(StatusCode.OK, opened.lifecycle.create(descriptor(), current, detail));
    before = allocationWatermark(opened.database);
    assertEquals(StatusCode.CONFLICT, opened.lifecycle.prepareSuccessor(
        current, current.descriptor(), inactive, prepared, detail));
    assertEquals(false, prepared.isActive());
    assertSameWatermark(before, allocationWatermark(opened.database));
    assertEquals(StatusCode.OK, current.release());
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void invalidSuccessorIdentityDoesNotAdvanceDurableAllocationWatermark(
      @TempDir Path root) {
    Opened opened = create(root);
    SchemaPin current = new SchemaPin();
    assertEquals(StatusCode.OK,
        opened.lifecycle.create(descriptor(), current, new StatusDetail(64)));
    CatalogAllocationWatermark before = allocationWatermark(opened.database);
    ColumnDescriptorSet.Result copiedColumns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT}, new CharSequence[] {"id"},
        new boolean[] {false}, copiedColumns));
    TableDescriptor.Result proposed = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createProposedSuccessor(
        current.tableId() + 1, current.rowLayoutId(), current.catalogGeneration(),
        copiedColumns.value(), null, null, null, proposed, null));
    IndexedTransactionSession publication = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    CatalogPreparedTable prepared = new CatalogPreparedTable();
    assertEquals(StatusCode.OK, publication.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.CONFLICT, opened.lifecycle.prepareSuccessor(
        current, proposed.value(), publication, prepared, new StatusDetail(64)));
    assertEquals(false, prepared.isActive());
    assertEquals(StatusCode.OK, publication.abort(outcome));
    CatalogAllocationWatermark after = allocationWatermark(opened.database);
    assertEquals(before.nextObjectId(), after.nextObjectId());
    assertEquals(before.nextSchemaId(), after.nextSchemaId());
    assertEquals(before.nextRowLayoutId(), after.nextRowLayoutId());
    assertEquals(before.nextCatalogRecordId(), after.nextCatalogRecordId());
    assertEquals(before.nextKeyId(), after.nextKeyId());
    assertEquals(StatusCode.OK, current.release());
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void dropRejectsStalePinAfterSuccessorPublication(@TempDir Path root) {
    Opened opened = create(root);
    StatusDetail detail = new StatusDetail(64);
    SchemaPin stale = new SchemaPin();
    assertEquals(StatusCode.OK, opened.lifecycle.create(descriptor(), stale, detail));
    long objectId = stale.tableId();
    long staleSchemaId = stale.schemaId();

    IndexedTransactionSession successorSession = session(opened.database);
    CatalogPreparedTable successor = new CatalogPreparedTable();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, successorSession.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, opened.lifecycle.prepareSuccessor(
        stale, stale.descriptor(), successorSession, successor, detail), detail.toString());
    assertEquals(StatusCode.OK, successorSession.commit(outcome));
    assertEquals(StatusCode.OK,
        opened.lifecycle.finish(successor, TransactionState.COMMITTED));

    IndexedTransactionSession dropSession = session(opened.database);
    assertEquals(StatusCode.OK, dropSession.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.CONFLICT,
        opened.lifecycle.prepareDrop(stale, dropSession, detail));
    assertEquals(0, dropSession.pendingMutationCount());
    assertEquals(StatusCode.OK, dropSession.abort(outcome));

    SchemaPin current = new SchemaPin();
    assertEquals(StatusCode.OK, opened.lifecycle.open(objectId, current, detail));
    assertNotEquals(staleSchemaId, current.schemaId());
    assertEquals(StatusCode.OK, current.release());
    assertEquals(StatusCode.OK, stale.release());
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void startupRejectsReadyHeadWithMissingManifest(@TempDir Path root) {
    Opened opened = create(root);
    ByteBuffer head = ByteBuffer.allocate(CatalogObjectHeadCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.encode(head, 0,
        CatalogObjectHeadCodec.STATE_READY, 7, 8, 1, 900, new CRC32C()));
    head.position(0).limit(CatalogObjectHeadCodec.BYTES);
    IndexedTransactionSession session = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.insert(CatalogKeyspace.OBJECT_HEAD_SPACE, 7, head));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.CORRUPTION, opened.lifecycle.validate());
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void startupReclaimsEveryRecordedUnfinishedBuildBoundary(@TempDir Path root)
      throws java.io.IOException {
    for (int written = 0; written <= 3; written++) {
      Opened opened = create(Files.createDirectory(root.resolve("p" + written)));
      long objectId = written + 1;
      long manifestId = 100 + written * 10L;
      long firstChild = manifestId + 1;
      IndexedTransactionSession session = session(opened.database);
      TransactionOutcome outcome = new TransactionOutcome();
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
      for (int index = 0; index < written; index++) {
        ByteBuffer privateRow = child(
            firstChild + index, objectId, objectId + 10, index);
        assertEquals(StatusCode.OK, session.insert(
            CatalogKeyspace.DEFINITION_SPACE, firstChild + index, privateRow));
      }
      ByteBuffer intent = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
      assertEquals(StatusCode.OK, CatalogBuildIntentCodec.encode(intent, 0,
          CatalogBuildIntentCodec.STATE_BUILDING, objectId, objectId + 10,
          objectId + 20, 1, manifestId, firstChild, 3, written, 0,
          128, 512, new CRC32C()));
      intent.position(0).limit(CatalogBuildIntentCodec.BYTES);
      assertEquals(StatusCode.OK, session.insert(
          CatalogKeyspace.BUILD_INTENT_SPACE, objectId, intent));
      assertEquals(StatusCode.OK, session.commit(outcome));

      assertEquals(StatusCode.OK, opened.lifecycle.validate());
      session = session(opened.database);
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      HeapRowResult row = new HeapRowResult();
      assertEquals(StatusCode.CONFLICT, session.fetchByKey(
          CatalogKeyspace.BUILD_INTENT_SPACE, objectId, row));
      for (int index = 0; index < written; index++) {
        assertEquals(StatusCode.CONFLICT, session.fetchByKey(
            CatalogKeyspace.DEFINITION_SPACE, firstChild + index, row));
      }
      assertEquals(StatusCode.OK, session.abort(outcome));
      assertEquals(StatusCode.OK, opened.database.close());
    }
  }

  @Test
  void cleanupRejectsWrongOwnedChildWithoutDeletingIt(@TempDir Path root) {
    Opened opened = create(root);
    long objectId = 7;
    long manifestId = 90;
    long childId = 91;
    IndexedTransactionSession session = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.insert(CatalogKeyspace.DEFINITION_SPACE,
        childId, child(childId, objectId + 1, 17, 0)));
    ByteBuffer intent = intent(objectId, 17, 18, manifestId, childId, 1, 1);
    assertEquals(StatusCode.OK,
        session.insert(CatalogKeyspace.BUILD_INTENT_SPACE, objectId, intent));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.CORRUPTION, opened.lifecycle.validate());
    session = session(opened.database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK,
        session.fetchByKey(CatalogKeyspace.DEFINITION_SPACE, childId, row));
    assertEquals(StatusCode.OK,
        session.fetchByKey(CatalogKeyspace.BUILD_INTENT_SPACE, objectId, row));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void historicalDescriptorLoadsDurablyAfterReopen(@TempDir Path root) {
    Opened opened = create(root);
    SchemaPin created = new SchemaPin();
    assertEquals(StatusCode.OK,
        opened.lifecycle.create(descriptor(), created, new StatusDetail(64)));
    long objectId = created.tableId();
    long rowLayoutId = created.rowLayoutId();
    long generation = created.catalogGeneration();
    assertEquals(StatusCode.OK, created.release());
    assertEquals(StatusCode.OK, opened.database.close());

    EmbeddedDatabaseOpenResult result = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.openExisting(runtimeRoot(), databasePlan(6), root, DATABASE, GENERATION, 6, result));
    SchemaCache.Result cache = new SchemaCache.Result();
    assertEquals(StatusCode.OK,
        SchemaCache.createBudgeted(8_000_000, cache, new StatusDetail(64)));
    CatalogTableLifecycle reopened = new CatalogTableLifecycle(result.database(), cache.value());
    assertEquals(StatusCode.OK, reopened.validate());
    SchemaPin historical = new SchemaPin();
    StatusDetail detail = new StatusDetail(64);
    assertEquals(StatusCode.OK, reopened.openHistorical(
        objectId, rowLayoutId, generation, historical, detail), detail.toString());
    assertEquals(generation, historical.catalogGeneration());
    assertEquals(rowLayoutId, historical.rowLayoutId());
    assertEquals(StatusCode.OK, historical.release());
    assertEquals(StatusCode.OK, result.database().close());
  }

  @Test
  void reopenCompletesReadyHeadWithResidualBuildingIntent(@TempDir Path root) {
    Opened opened = create(root);
    SchemaPin created = new SchemaPin();
    assertEquals(StatusCode.OK,
        opened.lifecycle.create(descriptor(), created, new StatusDetail(64)));
    long objectId = created.tableId();
    assertEquals(StatusCode.OK, created.release());

    IndexedTransactionSession session = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult row = new HeapRowResult();
    ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionRecordCodec.MAX_RECORD_BYTES);
    CRC32C checksum = new CRC32C();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK,
        session.fetchByKey(CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, row));
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    CatalogObjectHead head = new CatalogObjectHead();
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.decode(bytes, 0, head, checksum));
    bytes.clear();
    assertEquals(StatusCode.OK, session.fetchByKey(
        CatalogKeyspace.DEFINITION_SPACE, head.manifestRecordId(), row));
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    CatalogDefinitionManifest manifest = new CatalogDefinitionManifest();
    assertEquals(StatusCode.OK,
        CatalogDefinitionManifestCodec.decode(bytes, 0, manifest, checksum));
    assertEquals(StatusCode.OK, session.abort(outcome));

    long catalogBytes = manifest.payloadBytes()
        + (long) manifest.childCount() * CatalogDefinitionRecordCodec.HEADER_BYTES
        + CatalogDefinitionManifestCodec.BYTES + CatalogBuildIntentCodec.BYTES
        + CatalogObjectHeadCodec.BYTES;
    ByteBuffer intent = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogBuildIntentCodec.encode(intent, 0,
        CatalogBuildIntentCodec.STATE_BUILDING, objectId, manifest.schemaId(),
        manifest.rowLayoutId(), manifest.catalogGeneration(), manifest.catalogRecordId(),
        manifest.firstChildRecordId(), manifest.childCount(), manifest.childCount(), 0,
        manifest.payloadBytes(), catalogBytes, checksum));
    intent.position(0).limit(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.insert(CatalogKeyspace.BUILD_INTENT_SPACE, objectId, intent));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, opened.database.close());

    EmbeddedDatabaseOpenResult reopenedDatabase = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.openExisting(runtimeRoot(), databasePlan(6), root, DATABASE, GENERATION, 6, reopenedDatabase));
    SchemaCache.Result cache = new SchemaCache.Result();
    assertEquals(StatusCode.OK,
        SchemaCache.createBudgeted(8_000_000, cache, new StatusDetail(64)));
    CatalogTableLifecycle reopened = new CatalogTableLifecycle(
        reopenedDatabase.database(), cache.value());
    assertEquals(StatusCode.OK, reopened.validate());
    SchemaPin pin = new SchemaPin();
    assertEquals(StatusCode.OK,
        reopened.open(objectId, pin, new StatusDetail(64)));
    assertEquals(StatusCode.OK, pin.release());
    session = session(reopenedDatabase.database());
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT, session.fetchByKey(
        CatalogKeyspace.BUILD_INTENT_SPACE, objectId, row));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, reopenedDatabase.database().close());
  }

  @Test
  void readyIntentCannotExistBesideAnUnpublishedHead(@TempDir Path root) {
    Opened opened = create(root);
    SchemaPin created = new SchemaPin();
    assertEquals(StatusCode.OK,
        opened.lifecycle.create(descriptor(), created, new StatusDetail(64)));
    long objectId = created.tableId();
    assertEquals(StatusCode.OK, created.release());
    IndexedTransactionSession session = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult row = new HeapRowResult();
    ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionRecordCodec.MAX_RECORD_BYTES);
    CRC32C checksum = new CRC32C();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK,
        session.fetchByKey(CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, row));
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    CatalogObjectHead head = new CatalogObjectHead();
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.decode(bytes, 0, head, checksum));
    bytes.clear();
    assertEquals(StatusCode.OK, session.fetchByKey(
        CatalogKeyspace.DEFINITION_SPACE, head.manifestRecordId(), row));
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    CatalogDefinitionManifest manifest = new CatalogDefinitionManifest();
    assertEquals(StatusCode.OK,
        CatalogDefinitionManifestCodec.decode(bytes, 0, manifest, checksum));
    assertEquals(StatusCode.OK, session.abort(outcome));

    ByteBuffer building = ByteBuffer.allocate(CatalogObjectHeadCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.encode(building, 0,
        CatalogObjectHeadCodec.STATE_BUILDING, objectId, head.schemaId(),
        head.catalogGeneration(), head.manifestRecordId(), checksum));
    building.position(0).limit(CatalogObjectHeadCodec.BYTES);
    long catalogBytes = manifest.payloadBytes()
        + (long) manifest.childCount() * CatalogDefinitionRecordCodec.HEADER_BYTES
        + CatalogDefinitionManifestCodec.BYTES + CatalogBuildIntentCodec.BYTES
        + CatalogObjectHeadCodec.BYTES;
    ByteBuffer ready = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogBuildIntentCodec.encode(ready, 0,
        CatalogBuildIntentCodec.STATE_READY, objectId, manifest.schemaId(),
        manifest.rowLayoutId(), manifest.catalogGeneration(), manifest.catalogRecordId(),
        manifest.firstChildRecordId(), manifest.childCount(), manifest.childCount(), 0,
        manifest.payloadBytes(), catalogBytes, checksum));
    ready.position(0).limit(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.update(CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, building));
    assertEquals(StatusCode.OK,
        session.insert(CatalogKeyspace.BUILD_INTENT_SPACE, objectId, ready));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.CORRUPTION, opened.lifecycle.validate());
    session = session(opened.database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK,
        session.fetchByKey(CatalogKeyspace.BUILD_INTENT_SPACE, objectId, row));
    assertEquals(StatusCode.OK,
        session.fetchByKey(CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, row));
    bytes.clear();
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.decode(bytes, 0, head, checksum));
    assertEquals(CatalogObjectHeadCodec.STATE_BUILDING, head.state());
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void maximumObjectIdSurvivesReopenAndExhaustsAllocator(@TempDir Path root) {
    Opened opened = create(root);
    IndexedTransactionSession session = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer watermark = ByteBuffer.allocate(CatalogAllocationWatermarkCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogAllocationWatermarkCodec.encode(watermark, 0,
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID, 1, 1, 1, 1, new CRC32C()));
    watermark.position(0).limit(CatalogAllocationWatermarkCodec.BYTES);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.update(CatalogKeyspace.SYSTEM_SPACE,
        CatalogKeyspace.ALLOCATION_WATERMARK_KEY, watermark));
    assertEquals(StatusCode.OK, session.commit(outcome));

    SchemaPin created = new SchemaPin();
    assertEquals(StatusCode.OK,
        opened.lifecycle.create(descriptor(), created, new StatusDetail(64)));
    assertEquals(CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID, created.tableId());
    assertEquals(StatusCode.OK, created.release());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        opened.lifecycle.create(descriptor(), new SchemaPin(), new StatusDetail(64)));
    assertEquals(StatusCode.OK, opened.database.close());

    EmbeddedDatabaseOpenResult result = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.openExisting(runtimeRoot(), databasePlan(6), root, DATABASE, GENERATION, 6, result));
    SchemaCache.Result cache = new SchemaCache.Result();
    assertEquals(StatusCode.OK,
        SchemaCache.createBudgeted(8_000_000, cache, new StatusDetail(64)));
    CatalogTableLifecycle reopened = new CatalogTableLifecycle(result.database(), cache.value());
    assertEquals(StatusCode.OK, reopened.validate());
    SchemaPin pin = new SchemaPin();
    assertEquals(StatusCode.OK, reopened.open(
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID, pin, new StatusDetail(64)));
    assertEquals(CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID, pin.tableId());
    assertEquals(StatusCode.OK, pin.release());
    StatusDetail detail = new StatusDetail(64);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        reopened.open(CatalogKeyspace.OBJECT_ID_EXHAUSTED, pin, detail));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        reopened.openHistorical(
            CatalogKeyspace.OBJECT_ID_EXHAUSTED, 1, 1, pin, detail));
    assertEquals(StatusCode.OK, result.database().close());
  }

  @Test
  void preActiveFailureHasDefiniteAbortedState(@TempDir Path root) {
    Opened opened = create(root);
    CatalogTransactions transactions = new CatalogTransactions(opened.database);
    CatalogSessionResult result = new CatalogSessionResult();
    assertEquals(StatusCode.OK, transactions.open(result));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, result.session().begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, result.session().commit(outcome));
    assertEquals(TransactionState.COMMITTED, result.session().transaction().state());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        transactions.finish(result.session(), StatusCode.RESOURCE_EXHAUSTED, true));
    assertEquals(TransactionState.ABORTED, transactions.lastState());
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void catalogTransactionsReuseOnlyAfterTerminalOutcome(@TempDir Path root) {
    Opened opened = create(root);
    CatalogTransactions transactions = new CatalogTransactions(opened.database);
    CatalogSessionResult first = new CatalogSessionResult();
    CatalogSessionResult second = new CatalogSessionResult();
    CatalogSessionResult third = new CatalogSessionResult();
    assertEquals(StatusCode.OK, transactions.open(first));
    assertEquals(StatusCode.OK, transactions.open(second));
    assertEquals(false, first.session() == second.session());
    assertEquals(StatusCode.CONFLICT, transactions.open(third));
    assertEquals(StatusCode.OK, first.session().begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.session().begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, transactions.finish(first.session(), StatusCode.OK, false));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        transactions.finish(first.session(), StatusCode.OK, false));
    assertEquals(StatusCode.OK, transactions.finish(second.session(), StatusCode.OK, false));
    assertEquals(TransactionState.ABORTED, transactions.lastState());

    assertEquals(StatusCode.OK, transactions.open(third));
    assertEquals(true, first.session() == third.session());
    assertEquals(StatusCode.OK, third.session().begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, transactions.finish(third.session(), StatusCode.OK, false));
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void catalogBuildSessionKeepsItsLeaseAcrossTransactions(@TempDir Path root) {
    Opened opened = create(root);
    CatalogTransactions transactions = new CatalogTransactions(opened.database);
    CatalogSessionResult build = new CatalogSessionResult();
    CatalogSessionResult other = new CatalogSessionResult();
    assertEquals(StatusCode.OK, transactions.openBuild(build));
    assertEquals(StatusCode.OK, build.session().begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, transactions.finish(build.session(), StatusCode.OK, false));

    assertEquals(StatusCode.OK, transactions.open(other));
    assertEquals(false, build.session() == other.session());
    IndexedTransactionSession foreign = session(opened.database);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        transactions.finish(foreign, StatusCode.OK, false));
    assertEquals(StatusCode.OK, other.session().begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, transactions.finish(other.session(), StatusCode.OK, false));

    assertEquals(StatusCode.OK, build.session().begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, transactions.finish(build.session(), StatusCode.OK, false));
    assertEquals(StatusCode.OK, transactions.releaseBuild(build.session()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        transactions.releaseBuild(build.session()));
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void retainedCatalogSessionRechecksClosedDatabase(@TempDir Path root) {
    Opened opened = create(root);
    CatalogTransactions transactions = new CatalogTransactions(opened.database);
    CatalogSessionResult session = new CatalogSessionResult();
    assertEquals(StatusCode.OK, transactions.open(session));
    assertEquals(StatusCode.OK, session.session().begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, transactions.finish(session.session(), StatusCode.OK, false));
    assertEquals(StatusCode.OK, opened.database.close());
    assertEquals(StatusCode.CLOSED, transactions.open(session));
  }

  @Test
  void unknownCommittedIntentIsReconciledForInitialAndSuccessorBuilds(
      @TempDir Path root) throws java.io.IOException {
    for (int kind = 0; kind < 2; kind++) {
      Opened opened = create(Files.createDirectory(root.resolve("k" + kind)));
      SchemaPin current = new SchemaPin();
      if (kind != 0) {
        assertEquals(StatusCode.OK, opened.lifecycle.create(
            descriptor(), current, new StatusDetail(64)));
      }
      CatalogPreparedTable prepared = new CatalogPreparedTable();
      IndexedTransactionSession publication = session(opened.database);
      TransactionOutcome outcome = new TransactionOutcome();
      assertEquals(StatusCode.OK, publication.begin(IsolationLevel.SERIALIZABLE));
      StatusCode status = kind == 0
          ? opened.lifecycle.prepare(
              descriptor(), publication, prepared, new StatusDetail(64))
          : opened.lifecycle.prepareSuccessor(
              current, current.descriptor(), publication,
              prepared, new StatusDetail(64));
      assertEquals(StatusCode.OK, status);
      long objectId = prepared.objectId();
      rewindIntentToCreationBoundary(opened.database, objectId);
      prepared.forgetIntentCommitOutcome();
      assertEquals(StatusCode.OK, publication.abort(outcome));
      assertEquals(StatusCode.OK,
          opened.lifecycle.finish(prepared, TransactionState.ABORTED));
      assertEquals(false, prepared.isActive());
      assertEquals(0, opened.cache.reservedSlots());
      assertIntentMissing(opened.database, objectId);
      if (current.isActive()) assertEquals(StatusCode.OK, current.release());
      assertEquals(StatusCode.OK, opened.database.close());
    }
  }

  @Test
  void unknownAbortedIntentCancelsInitialAndSuccessorAdmissions(
      @TempDir Path root) throws java.io.IOException {
    for (int kind = CatalogBuildIntentCodec.KIND_INITIAL;
        kind <= CatalogBuildIntentCodec.KIND_SUCCESSOR; kind++) {
      Opened opened = create(Files.createDirectory(root.resolve("k" + kind)));
      CatalogPreparedTable prepared = new CatalogPreparedTable();
      CatalogReservation reservation = new CatalogReservation();
      if (kind == CatalogBuildIntentCodec.KIND_INITIAL) {
        reservation.setInitial(99, 199, 100, 101, 300, 301, 2, 400, 0);
      } else {
        reservation.setSuccessor(
            99, 199, 100, 101, 300, 301, 2, 400, 0, 198, 100, 299);
      }
      TableDescriptor target = descriptor();
      assertEquals(StatusCode.OK, opened.cache.reserveSuccessor(
          target, kind == CatalogBuildIntentCodec.KIND_INITIAL ? 0 : 100,
          prepared.admission()));
      prepared.activate(target, reservation, 128, 512);
      assertEquals(StatusCode.OK, completion(opened.database).finish(
          prepared, TransactionState.ABORTED, null));
      assertEquals(false, prepared.isActive());
      assertEquals(0, opened.cache.reservedSlots());
      assertEquals(StatusCode.OK, opened.database.close());
    }
  }

  @Test
  void unknownIntentRejectsProgressAndImmutableIdentityMismatch(@TempDir Path root) {
    Opened opened = create(root);
    CatalogPreparedTable prepared = new CatalogPreparedTable();
    IndexedTransactionSession publication = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, publication.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, opened.lifecycle.prepare(
        descriptor(), publication, prepared, new StatusDetail(64)));
    long objectId = prepared.objectId();
    prepared.forgetIntentCommitOutcome();
    CatalogIntentReconciliation reconciliation = new CatalogIntentReconciliation(
        new CatalogTransactions(opened.database), new CatalogIntentStore());
    assertEquals(StatusCode.CORRUPTION, reconciliation.reconcile(prepared));
    assertEquals(true, prepared.isActive());
    assertEquals(1, opened.cache.reservedSlots());

    rewindIntentToCreationBoundary(opened.database, objectId);
    rewriteIntentSchema(opened.database, objectId, 1);
    assertEquals(StatusCode.CORRUPTION, reconciliation.reconcile(prepared));
    assertEquals(true, prepared.isActive());
    rewriteIntentSchema(opened.database, objectId, -1);
    assertEquals(StatusCode.OK, reconciliation.reconcile(prepared));
    assertEquals(true, reconciliation.found());

    assertEquals(StatusCode.OK, publication.abort(outcome));
    assertEquals(StatusCode.OK,
        opened.lifecycle.finish(prepared, TransactionState.ABORTED));
    assertEquals(false, prepared.isActive());
    assertEquals(0, opened.cache.reservedSlots());
    assertIntentMissing(opened.database, objectId);
    assertEquals(StatusCode.OK, opened.database.close());
  }

  @Test
  void startupRejectsLoneBuildingHead(@TempDir Path root) {
    Opened opened = create(root);
    SchemaPin created = new SchemaPin();
    assertEquals(StatusCode.OK,
        opened.lifecycle.create(descriptor(), created, new StatusDetail(64)));
    long objectId = created.tableId();
    long generation = created.catalogGeneration();
    assertEquals(StatusCode.OK, created.release());

    IndexedTransactionSession session = session(opened.database);
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult stored = new HeapRowResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK,
        session.fetchByKey(CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, stored));
    ByteBuffer bytes = ByteBuffer.allocate(CatalogObjectHeadCodec.BYTES);
    assertEquals(StatusCode.OK, stored.copyTo(bytes));
    bytes.flip();
    CatalogObjectHead ready = new CatalogObjectHead();
    assertEquals(StatusCode.OK,
        CatalogObjectHeadCodec.decode(bytes, 0, ready, new CRC32C()));
    assertEquals(StatusCode.OK, session.abort(outcome));

    bytes.clear();
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.encode(
        bytes, 0, CatalogObjectHeadCodec.STATE_BUILDING, objectId, ready.schemaId(),
        generation, ready.manifestRecordId(), new CRC32C()));
    bytes.position(0).limit(CatalogObjectHeadCodec.BYTES);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.update(CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, bytes));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.CORRUPTION, opened.lifecycle.validate());
    assertEquals(StatusCode.OK, opened.database.close());
  }

  private static Opened create(Path root) {
    EmbeddedDatabaseOpenResult result = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.create(runtimeRoot(), databasePlan(6), root, DATABASE, GENERATION, 6, result));
    SchemaCache.Result cache = new SchemaCache.Result();
    assertEquals(StatusCode.OK,
        SchemaCache.createBudgeted(8_000_000, cache, new StatusDetail(64)));
    CatalogTableLifecycle lifecycle = new CatalogTableLifecycle(
        result.database(), cache.value());
    assertEquals(StatusCode.OK, lifecycle.initialize());
    return new Opened(result.database(), lifecycle, cache.value());
  }

  private static IndexedTransactionSession session(EmbeddedDatabase database) {
    EmbeddedSessionOpenResult result = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK,
        database.createSession(CatalogBuildIntentCodec.BYTES, result));
    return result.session();
  }

  private static void assertPublishedRoot(
      EmbeddedDatabase database, TableDescriptor table, long keyId) {
    IndexedTransactionSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTupleIndexState state = new IndexedTupleIndexState();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.readTupleIndexState(keyId, state));
    assertEquals(TupleIndexRootRecordCodec.STATE_READY, state.state());
    assertEquals(table.tableId(), state.ownerObjectId());
    assertEquals(keyId, state.schemaId());
    assertEquals(0, state.privateOwner());
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, session.close());
  }

  private static void assertAbsentRoot(EmbeddedDatabase database, long keyId) {
    IndexedTransactionSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTupleIndexState state = new IndexedTupleIndexState();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.readTupleIndexState(keyId, state));
    assertEquals(TupleIndexRootRecordCodec.STATE_ABSENT, state.state());
    assertEquals(0, state.rootPageId());
    assertEquals(0, state.privateOwner());
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, session.close());
  }

  private static CatalogAllocationWatermark allocationWatermark(
      EmbeddedDatabase database) {
    IndexedTransactionSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult row = new HeapRowResult();
    ByteBuffer bytes = ByteBuffer.allocate(CatalogAllocationWatermarkCodec.BYTES);
    CatalogAllocationWatermark watermark = new CatalogAllocationWatermark();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.fetchByKey(
        CatalogKeyspace.SYSTEM_SPACE,
        CatalogKeyspace.ALLOCATION_WATERMARK_KEY, row));
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    assertEquals(StatusCode.OK, CatalogAllocationWatermarkCodec.decode(
        bytes, 0, watermark, new CRC32C()));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, session.close());
    return watermark;
  }

  private static void assertSameWatermark(
      CatalogAllocationWatermark expected, CatalogAllocationWatermark actual) {
    assertEquals(expected.nextObjectId(), actual.nextObjectId());
    assertEquals(expected.nextSchemaId(), actual.nextSchemaId());
    assertEquals(expected.nextRowLayoutId(), actual.nextRowLayoutId());
    assertEquals(expected.nextCatalogRecordId(), actual.nextCatalogRecordId());
    assertEquals(expected.nextKeyId(), actual.nextKeyId());
  }

  private static CatalogPreparedTableCompletion completion(EmbeddedDatabase database) {
    CatalogTransactions transactions = new CatalogTransactions(database);
    CatalogIntentStore intents = new CatalogIntentStore();
    CatalogBuildCleaner cleaner = new CatalogBuildCleaner(
        transactions, intents, new CatalogDefinitionStore());
    return new CatalogPreparedTableCompletion(transactions, intents, cleaner);
  }

  private static void assertIntentMissing(EmbeddedDatabase database, long objectId) {
    IndexedTransactionSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT, session.fetchByKey(
        CatalogKeyspace.BUILD_INTENT_SPACE, objectId, row));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, session.close());
  }

  private static void rewindIntentToCreationBoundary(
      EmbeddedDatabase database, long objectId) {
    IndexedTransactionSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    CatalogIntentStore intents = new CatalogIntentStore();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, intents.read(session, objectId));
    CatalogBuildIntent intent = intents.value();
    assertDeleteIfPresent(session, intent.manifestRecordId());
    for (int index = 0; index < intent.childCount(); index++) {
      assertDeleteIfPresent(session, intent.firstChildRecordId() + index);
    }
    ByteBuffer bytes = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
    StatusCode status = CatalogBuildIntentCodec.encodeWithCleanupHorizon(
        bytes, 0, intent.state(), intent.kind(), intent.objectId(), intent.schemaId(),
        intent.rowLayoutId(), intent.catalogGeneration(), intent.manifestRecordId(),
        intent.firstChildRecordId(), intent.childCount(), 0, 0,
        intent.payloadBytes(), intent.catalogBytes(), intent.predecessorSchemaId(),
        intent.predecessorGeneration(), intent.predecessorManifestRecordId(),
        intent.firstKeyId(), intent.keyCount(), intent.physicalIndexCount(),
        0, 0, 0, new CRC32C());
    assertEquals(StatusCode.OK, status);
    bytes.position(0).limit(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, session.update(
        CatalogKeyspace.BUILD_INTENT_SPACE, objectId, bytes));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.close());
  }

  private static void assertDeleteIfPresent(
      IndexedTransactionSession session, long recordId) {
    StatusCode status = session.delete(CatalogKeyspace.DEFINITION_SPACE, recordId);
    assertEquals(StatusCode.OK, status);
  }

  private static void rewriteIntentSchema(
      EmbeddedDatabase database, long objectId, long schemaDelta) {
    IndexedTransactionSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    CatalogIntentStore intents = new CatalogIntentStore();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, intents.read(session, objectId));
    CatalogBuildIntent intent = intents.value();
    ByteBuffer bytes = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
    long schemaId = intent.schemaId() + schemaDelta;
    StatusCode status = CatalogBuildIntentCodec.encodeWithCleanupHorizon(
        bytes, 0, intent.state(), intent.kind(), intent.objectId(), schemaId,
        intent.rowLayoutId(), intent.catalogGeneration(), intent.manifestRecordId(),
        intent.firstChildRecordId(), intent.childCount(), intent.nextChild(),
        intent.cleanupCursor(), intent.payloadBytes(), intent.catalogBytes(),
        intent.predecessorSchemaId(), intent.predecessorGeneration(),
        intent.predecessorManifestRecordId(), intent.firstKeyId(), intent.keyCount(),
        intent.physicalIndexCount(), intent.nextPhysicalIndex(),
        intent.indexCleanupCursor(), intent.indexCleanupHorizon(), new CRC32C());
    assertEquals(StatusCode.OK, status);
    bytes.position(0).limit(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, session.update(
        CatalogKeyspace.BUILD_INTENT_SPACE, objectId, bytes));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.close());
  }

  private static ByteBuffer child(
      long recordId, long objectId, long schemaId, int ordinal) {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionRecordCodec.HEADER_BYTES + 1);
    ByteBuffer payload = ByteBuffer.wrap(new byte[] {(byte) ordinal});
    assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.encode(bytes, 0,
        recordId, objectId, schemaId, 1, CatalogDefinitionRecordCodec.KIND_COLUMNS,
        ordinal, ordinal, 1, payload, new CRC32C()));
    bytes.position(0).limit(CatalogDefinitionRecordCodec.HEADER_BYTES + 1);
    return bytes;
  }

  private static ByteBuffer intent(
      long objectId, long schemaId, long layoutId,
      long manifestId, long childId, int childCount, int nextChild) {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogBuildIntentCodec.encode(bytes, 0,
        CatalogBuildIntentCodec.STATE_BUILDING, objectId, schemaId, layoutId, 1,
        manifestId, childId, childCount, nextChild, 0, 128, 512, new CRC32C()));
    bytes.position(0).limit(CatalogBuildIntentCodec.BYTES);
    return bytes;
  }

  private static TableDescriptor descriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT}, new CharSequence[] {"id"},
        new boolean[] {false}, columns));
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        99, 100, 101, columns.value(), null, null, null, result, null));
    return result.value();
  }

  private record Opened(
      EmbeddedDatabase database, CatalogTableLifecycle lifecycle, SchemaCache cache) {
  }
}
