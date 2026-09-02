package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.runtime.DatabaseResourceDefaults;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.engine.table.IndexedRelationalMutation;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalDescriptorRowPathTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x57494445524f5750L, 0x4154485445535431L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int COLUMN_COUNT = 1_024;
  private static final int[] NULL_ORDINALS = {7, 8, 63, 64, 255, 1_023};

  @Test
  void foreignPublishedPinCannotAccessRowsDespiteCollidingIds(@TempDir Path root)
      throws java.io.IOException {
    RelationalDatabaseOpenResult firstOpen = new RelationalDatabaseOpenResult();
    RelationalDatabaseOpenResult secondOpen = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        Files.createDirectory(root.resolve("first")),
        DATABASE, GENERATION, 8, firstOpen));
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        Files.createDirectory(root.resolve("second")),
        DatabaseIncarnation.of(0x57494445524f5750L, 0x4154485445535432L),
        GENERATION, 8, secondOpen));
    RelationalDatabase first = firstOpen.database();
    RelationalDatabase second = secondOpen.database();
    SchemaPin foreign = new SchemaPin();
    SchemaPin local = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK,
        first.services().descriptors().create(wideDescriptor(), foreign, detail));
    assertEquals(StatusCode.OK,
        second.services().descriptors().create(wideDescriptor(), local, detail));
    assertEquals(foreign.tableId(), local.tableId());
    RelationalSession session = session(second);
    TransactionOutcome outcome = new TransactionOutcome();
    SqlValueBuffer values = values(41, NULL_ORDINALS);
    SqlValueBuffer fetched = emptyValues();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.descriptorRows().insert(
            foreign, values, new RelationalRowIdentityResult()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.descriptorRows().fetch(foreign, 41, fetched));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.descriptorRows().update(foreign, 41, values));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.descriptorRows().delete(foreign, 41));
    assertEquals(0, session.indexedSession().pendingMutationCount());
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().fetch(local, 41, fetched));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, foreign.release());
    assertEquals(StatusCode.OK, local.release());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
  }

  @Test
  void wideRowNullsIdentityAndPrimaryMutationSurviveAbortAndReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK,
        database.services().descriptors().create(
            wideDescriptor(), table, detail), detail.toString());
    long objectId = table.tableId();

    RelationalSession session = session(database);
    SqlValueBuffer input = values(10, NULL_ORDINALS);
    RelationalRowIdentityResult first = new RelationalRowIdentityResult();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(table, input, first));
    assertEquals(1, first.logicalRowId());
    assertEquals(StatusCode.OK, session.abort(outcome));

    RelationalRowIdentityResult committed = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(11, NULL_ORDINALS), committed));
    assertEquals(2, committed.logicalRowId());
    assertEquals(StatusCode.OK, session.commit(outcome));

    SqlValueBuffer fetched = emptyValues();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().fetchByLogicalRowId(table, 1, fetched));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 11, fetched));
    assertWideValues(fetched, 11, NULL_ORDINALS);
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 11, values(12, new int[] {8, 64, 255})));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK,
        database.services().descriptors().open(objectId, table, detail), detail.toString());
    session = session(database);
    fetched = emptyValues();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 11, fetched));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 12, fetched));
    assertWideValues(fetched, 12, new int[] {8, 64, 255});
    assertEquals(StatusCode.OK,
        session.descriptorRows().fetchByLogicalRowId(
            table, committed.logicalRowId(), fetched));
    assertEquals(12, fetched.valueAt(0));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    RelationalRowIdentityResult scanned = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.descriptorRows().beginScan(table, cursor));
    assertFalse(table.isActive());
    assertEquals(StatusCode.OK,
        session.descriptorRows().nextScan(cursor, fetched, scanned));
    assertEquals(committed.logicalRowId(), scanned.logicalRowId());
    assertTrue(fetched.isNull(255));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().nextScan(cursor, fetched, scanned));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, session.commit(outcome));

    table = new SchemaPin();
    assertEquals(StatusCode.OK,
        database.services().descriptors().open(objectId, table, detail), detail.toString());
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().delete(table, 12));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 12, fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void descriptorNamespacesStayBetweenLegacyAndCatalogRanges() {
    assertEquals((long) RelationalKey.MAXIMUM_TABLE_ID,
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID);
    assertEquals(StatusCode.OK, RelationalDescriptorKeyspace.validate(1));
    assertTrue(RelationalDescriptorKeyspace.baseRows(1) > RelationalKey.auxiliarySpace(
        RelationalKey.MAXIMUM_TABLE_ID));
    assertEquals(StatusCode.OK,
        RelationalDescriptorKeyspace.validate(CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID));
    assertTrue(RelationalDescriptorKeyspace.baseRows(
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID) < CatalogKeyspace.SYSTEM_SPACE);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        RelationalDescriptorKeyspace.validate(
            CatalogKeyspace.OBJECT_ID_EXHAUSTED));
  }

  @Test
  void tuplePrimaryCorruptionBlocksPointAccessAndFailsReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK,
        database.services().descriptors().create(wideDescriptor(), table, detail));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    RelationalRowIdentityResult inserted = new RelationalRowIdentityResult();
    RelationalRowIdentityResult victim = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(77, NULL_ORDINALS), inserted));
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(88, NULL_ORDINALS), victim));
    assertEquals(StatusCode.OK, session.commit(outcome));

    SqlValueBuffer primaryValues = values(77, NULL_ORDINALS);
    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    assertEquals(StatusCode.OK, encoder.encodePhysical(
        table.descriptor().primaryKey(), primaryValues, inserted.logicalRowId()));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.indexedSession().preflightTupleMutations(1, 0, encoder.length()));
    assertEquals(StatusCode.OK, session.indexedSession().appendTupleMutation(
        IndexedRelationalMutation.TUPLE_DELETE,
        table.tableId(), table.descriptor().primaryKey().keyId(),
        table.descriptor().primaryKey().keyId(), table.descriptor().primaryKey().shape(),
        inserted.logicalRowId(), encoder.bytes(), 0, encoder.length()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().fetchByLogicalRowId(
        table, inserted.logicalRowId(), emptyValues()));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().fetch(table, 77, emptyValues()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().update(table, 77, values(79, NULL_ORDINALS)));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().delete(table, 77));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    SqlValueBuffer surviving = emptyValues();
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 88, surviving));
    assertEquals(88, surviving.valueAt(0));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void scanAdmissionGrowsCallerBufferBeforeConsumingTheFirstRow(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        wideDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    RelationalRowIdentityResult inserted = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(41, NULL_ORDINALS), inserted));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    assertEquals(StatusCode.OK, session.descriptorRows().beginScan(table, cursor));
    SqlValueBuffer destination = new SqlValueBuffer();
    assertEquals(StatusCode.OK, destination.reserve(1, 1, 4, 4));
    assertEquals(StatusCode.OK, destination.clearForSize(1));
    assertEquals(StatusCode.OK,
        destination.setText(0, SqlTypeDescriptor.varchar(1), "x"));
    RelationalRowIdentityResult result = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        session.descriptorRows().nextScan(cursor, destination, result));
    assertEquals(inserted.logicalRowId(), result.logicalRowId());
    assertEquals(41, destination.valueAt(0));
    assertEquals(COLUMN_COUNT, destination.count());
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().nextScan(cursor, destination, result));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void groupedMutationsGrowBeyondDefaultWriteCounts(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        wideDescriptor(), table, new StatusDetail(128)));
    long objectId = table.tableId();
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer oneByte = ByteBuffer.wrap(new byte[] {1});
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    for (int index = 0;
        index < DatabaseResourceDefaults.TRANSACTION_WRITE_ENTRIES - 1;
        index++) {
      oneByte.position(0);
      assertEquals(StatusCode.OK, session.indexedSession().insert(10, index, oneByte));
    }
    RelationalRowIdentityResult accepted = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(51, NULL_ORDINALS), accepted));
    assertEquals(1, accepted.logicalRowId());
    assertEquals(
        DatabaseResourceDefaults.TRANSACTION_WRITE_ENTRIES,
        session.indexedSession().pendingMutationCount());
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    for (int index = 0; index < 382; index++) {
      oneByte.position(0);
      assertEquals(StatusCode.OK, session.indexedSession().insert(11, index, oneByte));
    }
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 51, values(52, NULL_ORDINALS)));
    assertEquals(383, session.indexedSession().pendingMutationCount());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        objectId, table, new StatusDetail(128)));
    session = session(database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    SqlValueBuffer fetched = emptyValues();
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 51, fetched));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 52, fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void updateStagesOnlyKeysWhoseCanonicalBytesChange(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, indexedPayloadValues(1, 10, 100), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 1, indexedPayloadValues(1, 10, 101)));
    assertEquals(0, session.indexedSession().pendingTupleMutationCount());
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 1, indexedPayloadValues(1, 11, 102)));
    assertEquals(2, session.indexedSession().pendingTupleMutationCount());
    assertEquals(StatusCode.OK, session.commit(outcome));

    SqlValueBuffer fetched = indexedPayloadValues(0, 0, 0);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, fetched));
    assertEquals(11, fetched.valueAt(1));
    assertEquals(102, fetched.valueAt(2));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void explicitTransactionRollsBackPartialBatchThenCommitsAndReopens(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK,
        database.services().descriptors().create(wideDescriptor(), table, detail));
    long objectId = table.tableId();
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedSavepoint statement = new IndexedSavepoint();
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch();
    RelationalDescriptorBatchInsert inserts = session.descriptorRows().batchInsert();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    RelationalRowIdentityResult first = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(101, NULL_ORDINALS), first));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 101, values(102, NULL_ORDINALS)));
    assertEquals(StatusCode.OK, session.createSavepoint(statement));
    SqlValueBuffer batchFirst = values(201, NULL_ORDINALS);
    SqlValueBuffer batchSecond = values(202, NULL_ORDINALS);
    assertEquals(StatusCode.OK, inserts.begin(batch, table, 2));
    assertEquals(StatusCode.OK, inserts.admit(batch, table, batchFirst));
    assertEquals(StatusCode.OK, inserts.admit(batch, table, batchSecond));
    assertEquals(StatusCode.OK, inserts.reserve(batch, table));
    RelationalRowIdentityResult staged = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, inserts.insert(batch, table, 0, batchFirst, staged));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, inserts.insert(
        batch, table, 1, values(999, NULL_ORDINALS),
        new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(statement));
    batch.reset();
    SqlValueBuffer fetched = emptyValues();
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 101, fetched));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 102, fetched));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 201, fetched));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().fetchByLogicalRowId(table, staged.logicalRowId(), fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK,
        database.services().descriptors().open(objectId, table, detail));
    session = session(database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 101, fetched));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 102, fetched));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 201, fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void preparedReservationsSurviveDmlRollbackWithoutReusingIdentity(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin table = new SchemaPin();
    IndexedSavepoint rows = new IndexedSavepoint();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.prepareDescriptorTable("prepared_ids", wideDescriptor(), detail),
        detail.toString());
    assertEquals(StatusCode.OK, session.resolveDescriptor("prepared_ids", table, detail));
    assertFalse(table.isPublished());
    assertEquals(StatusCode.OK, session.createSavepoint(rows));
    RelationalRowIdentityResult rolledBack = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(61, NULL_ORDINALS), rolledBack));
    assertEquals(1, rolledBack.logicalRowId());
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(rows));

    RelationalRowIdentityResult committed = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(62, NULL_ORDINALS), committed));
    assertEquals(2, committed.logicalRowId());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertTrue(table.isPublished());

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    SqlValueBuffer fetched = emptyValues();
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().fetchByLogicalRowId(table, 1, fetched));
    assertEquals(StatusCode.OK,
        session.descriptorRows().fetchByLogicalRowId(table, 2, fetched));
    assertEquals(62, fetched.valueAt(0));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rolledBackPreparedPinCannotEscapeAndCleanupRetriesAfterRelease(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin stale = new SchemaPin();
    IndexedSavepoint beforeCreate = new IndexedSavepoint();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.createSavepoint(beforeCreate));
    assertEquals(StatusCode.OK,
        session.prepareDescriptorTable("discarded", wideDescriptor(), detail),
        detail.toString());
    assertEquals(StatusCode.OK, session.resolveDescriptor("discarded", stale, detail));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(beforeCreate));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.descriptorRows().insert(
            stale, values(71, NULL_ORDINALS), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.CONFLICT, session.abort(outcome));

    assertEquals(StatusCode.OK, stale.release());
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT,
        session.resolveDescriptor("discarded", new SchemaPin(), detail));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void insertBatchGrowsBeyondDefaultWriteCountAndReservesAsOneRange(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        wideDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer oneByte = ByteBuffer.wrap(new byte[] {1});
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch();
    RelationalDescriptorBatchInsert inserts = session.descriptorRows().batchInsert();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    for (int index = 0;
        index < DatabaseResourceDefaults.TRANSACTION_WRITE_ENTRIES - 1;
        index++) {
      oneByte.position(0);
      assertEquals(StatusCode.OK, session.indexedSession().insert(20, index, oneByte));
    }
    assertEquals(StatusCode.OK, inserts.begin(batch, table, 2));
    assertEquals(StatusCode.OK,
        inserts.admit(batch, table, values(81, NULL_ORDINALS)));
    assertEquals(StatusCode.OK,
        inserts.admit(batch, table, values(82, NULL_ORDINALS)));
    assertEquals(StatusCode.OK,
        inserts.reserve(batch, table));
    assertEquals(StatusCode.OK, session.abort(outcome));

    batch.reset();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, inserts.begin(batch, table, 2));
    SqlValueBuffer first = values(81, NULL_ORDINALS);
    SqlValueBuffer second = values(82, NULL_ORDINALS);
    assertEquals(StatusCode.OK, inserts.admit(batch, table, first));
    assertEquals(StatusCode.OK, inserts.admit(batch, table, second));
    assertEquals(StatusCode.OK, inserts.reserve(batch, table));
    RelationalRowIdentityResult firstId = new RelationalRowIdentityResult();
    RelationalRowIdentityResult secondId = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        inserts.insert(batch, table, 0, first, firstId));
    assertEquals(StatusCode.OK,
        inserts.insert(batch, table, 1, second, secondId));
    assertEquals(3, firstId.logicalRowId());
    assertEquals(4, secondId.logicalRowId());
    assertEquals(StatusCode.OK, session.commit(outcome));
    batch.reset();
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void insertBatchReceiptRejectsSecondReservationAndChangedEncodedLength(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        textDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    RelationalDescriptorBatchInsert inserts = session.descriptorRows().batchInsert();
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch();
    TransactionOutcome outcome = new TransactionOutcome();
    SqlValueBuffer admitted = textValues(91, "a");

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, inserts.begin(batch, table, 1));
    assertEquals(StatusCode.OK, inserts.admit(batch, table, admitted));
    assertEquals(StatusCode.OK, inserts.reserve(batch, table));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, inserts.reserve(batch, table));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, inserts.insert(
        batch, table, 0, textValues(91, "a larger admitted payload"),
        new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.abort(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    RelationalRowIdentityResult inserted = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.descriptorRows().insert(table, admitted, inserted));
    assertEquals(2, inserted.logicalRowId());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  private static RelationalSession session(RelationalDatabase database) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    return opened.session();
  }

  private static SqlValueBuffer values(long key, int[] nullOrdinals) {
    SqlValueBuffer values = emptyValues();
    assertEquals(StatusCode.OK, values.clearForSize(COLUMN_COUNT));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, key));
    for (int ordinal = 1; ordinal < COLUMN_COUNT; ordinal++) {
      assertEquals(StatusCode.OK, contains(nullOrdinals, ordinal)
          ? values.setNull(ordinal, SqlTypeDescriptor.BOOLEAN)
          : values.setFixed(ordinal, SqlTypeDescriptor.BOOLEAN, ordinal & 1));
    }
    return values;
  }

  private static SqlValueBuffer emptyValues() {
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(COLUMN_COUNT, COLUMN_COUNT, 0, 0));
    return values;
  }

  private static void assertWideValues(
      SqlValueBuffer values, long key, int[] nullOrdinals) {
    assertEquals(COLUMN_COUNT, values.count());
    assertEquals(key, values.valueAt(0));
    assertFalse(values.isNull(0));
    for (int ordinal : new int[] {7, 8, 63, 64, 255, 1_023}) {
      assertEquals(contains(nullOrdinals, ordinal), values.isNull(ordinal));
      if (!values.isNull(ordinal)) assertEquals(ordinal & 1, values.valueAt(ordinal));
    }
  }

  private static boolean contains(int[] values, int candidate) {
    for (int value : values) if (value == candidate) return true;
    return false;
  }

  private static TableDescriptor wideDescriptor() {
    int[] types = new int[COLUMN_COUNT];
    CharSequence[] names = new CharSequence[COLUMN_COUNT];
    boolean[] nullable = new boolean[COLUMN_COUNT];
    for (int ordinal = 0; ordinal < COLUMN_COUNT; ordinal++) {
      types[ordinal] = ordinal == 0
          ? SqlTypeDescriptor.BIGINT : SqlTypeDescriptor.BOOLEAN;
      names[ordinal] = "c" + ordinal;
      nullable[ordinal] = ordinal != 0;
    }
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(types, names, nullable, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        1, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        1, 1, 1, columns.value(), primary.value(), null, null, table, null));
    return table.value();
  }

  private static TableDescriptor textDescriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(64)},
        new CharSequence[] {"id", "value"}, new boolean[] {false, false}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        1, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        1, 1, 1, columns.value(), primary.value(), null, null, table, null));
    return table.value();
  }

  private static TableDescriptor indexedPayloadDescriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT,
            SqlTypeDescriptor.BIGINT
        },
        new CharSequence[] {"id", "indexed_value", "payload"},
        new boolean[] {false, false, false}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        1, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    KeyDescriptor.Result secondary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
        2, KeyDescriptor.KIND_SECONDARY, false, columns.value(), new int[] {1},
        0, "by_indexed_value", secondary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        1, 1, 1, columns.value(), primary.value(),
        new KeyDescriptor[] {secondary.value()}, null, table, null));
    return table.value();
  }

  private static SqlValueBuffer indexedPayloadValues(
      long id, long indexedValue, long payload) {
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(3, 3, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(3));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, id));
    assertEquals(StatusCode.OK,
        values.setFixed(1, SqlTypeDescriptor.BIGINT, indexedValue));
    assertEquals(StatusCode.OK,
        values.setFixed(2, SqlTypeDescriptor.BIGINT, payload));
    return values;
  }

  private static SqlValueBuffer textValues(long key, CharSequence text) {
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(2, 2, 256, 256));
    assertEquals(StatusCode.OK, values.clearForSize(2));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, key));
    assertEquals(StatusCode.OK,
        values.setText(1, SqlTypeDescriptor.varchar(64), text));
    return values;
  }
}
