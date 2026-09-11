package io.riverdb.engine.schema.catalog;

import static io.riverdb.engine.TestDatabaseResources.databasePlan;
import static io.riverdb.engine.TestDatabaseResources.runtimeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleIndexState;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogAllocationWatermark;
import io.riverdb.format.catalog.CatalogAllocationWatermarkCodec;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogBuildIntentCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32C;

final class CatalogLifecycleRemediationTestSupport {
  static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x43415452454d4544L, 0x494154494f4e3031L);
  static final WalGeneration GENERATION = WalGeneration.of(1);

  static Opened create(Path root) {
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

  static IndexedTransactionSession session(EmbeddedDatabase database) {
    EmbeddedSessionOpenResult result = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK,
        database.createSession(CatalogBuildIntentCodec.BYTES, result));
    return result.session();
  }

  static void assertPublishedRoot(
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

  static void assertAbsentRoot(EmbeddedDatabase database, long keyId) {
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

  static CatalogAllocationWatermark allocationWatermark(
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

  static void assertSameWatermark(
      CatalogAllocationWatermark expected, CatalogAllocationWatermark actual) {
    assertEquals(expected.nextObjectId(), actual.nextObjectId());
    assertEquals(expected.nextSchemaId(), actual.nextSchemaId());
    assertEquals(expected.nextRowLayoutId(), actual.nextRowLayoutId());
    assertEquals(expected.nextCatalogRecordId(), actual.nextCatalogRecordId());
    assertEquals(expected.nextKeyId(), actual.nextKeyId());
  }

  static CatalogPreparedTableCompletion completion(EmbeddedDatabase database) {
    CatalogTransactions transactions = new CatalogTransactions(database);
    CatalogIntentStore intents = new CatalogIntentStore();
    CatalogBuildCleaner cleaner = new CatalogBuildCleaner(
        transactions, intents, new CatalogDefinitionStore());
    return new CatalogPreparedTableCompletion(transactions, intents, cleaner);
  }

  static void assertIntentMissing(EmbeddedDatabase database, long objectId) {
    IndexedTransactionSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT, session.fetchByKey(
        CatalogKeyspace.BUILD_INTENT_SPACE, objectId, row));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, session.close());
  }

  static void rewindIntentToCreationBoundary(
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

  static void assertDeleteIfPresent(
      IndexedTransactionSession session, long recordId) {
    StatusCode status = session.delete(CatalogKeyspace.DEFINITION_SPACE, recordId);
    assertEquals(StatusCode.OK, status);
  }

  static void rewriteIntentSchema(
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

  static ByteBuffer child(
      long recordId, long objectId, long schemaId, int ordinal) {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionRecordCodec.HEADER_BYTES + 1);
    ByteBuffer payload = ByteBuffer.wrap(new byte[] {(byte) ordinal});
    assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.encode(bytes, 0,
        recordId, objectId, schemaId, 1, CatalogDefinitionRecordCodec.KIND_COLUMNS,
        ordinal, ordinal, 1, payload, new CRC32C()));
    bytes.position(0).limit(CatalogDefinitionRecordCodec.HEADER_BYTES + 1);
    return bytes;
  }

  static ByteBuffer intent(
      long objectId, long schemaId, long layoutId,
      long manifestId, long childId, int childCount, int nextChild) {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogBuildIntentCodec.encode(bytes, 0,
        CatalogBuildIntentCodec.STATE_BUILDING, objectId, schemaId, layoutId, 1,
        manifestId, childId, childCount, nextChild, 0, 128, 512, new CRC32C()));
    bytes.position(0).limit(CatalogBuildIntentCodec.BYTES);
    return bytes;
  }

  static TableDescriptor descriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT}, new CharSequence[] {"id"},
        new boolean[] {false}, columns));
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        99, 100, 101, columns.value(), null, null, null, result, null));
    return result.value();
  }

  record Opened(
      EmbeddedDatabase database, CatalogTableLifecycle lifecycle, SchemaCache cache) {
  }
}
