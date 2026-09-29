package io.riverdb.engine.relational;

import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.engine.table.IndexedRelationalMutation;
import io.riverdb.engine.table.IndexedTupleIndexState;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import io.riverdb.sql.SqlComparison;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.lock.LockMode;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalDescriptorRowPathTest {
  private static final int FORMER_DEFAULT_WRITE_ENTRIES = 384;
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x57494445524f5750L, 0x4154485445535431L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int COLUMN_COUNT = 1_024;
  private static final int[] NULL_ORDINALS = {7, 8, 63, 64, 255, 1_023};

  @Test
  void malformedExternalTextKeyCannotDeleteCanonicalRow(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin pin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        textPrimaryDescriptor(), pin, new StatusDetail(128)));
    int descriptor = SqlTypeDescriptor.varchar(8);
    SqlMutationValues valid = new SqlMutationValues();
    assertEquals(StatusCode.OK, valid.reserve(pin.descriptor(), 8));
    assertEquals(StatusCode.OK, valid.begin(pin.descriptor(), null));
    assertEquals(StatusCode.OK, valid.setText(0, descriptor, Character.toString((char) 1)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(pin, valid, new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    byte[] overlong = {(byte) 0xc0, (byte) 0x81};
    SqlValueAccess forged = new SqlValueAccess() {
      @Override public int count() { return 1; }
      @Override public int descriptorAt(int column) { return descriptor; }
      @Override public boolean isNull(int column) { return false; }
      @Override public long valueAt(int column) { return 0; }
      @Override public long highValueAt(int column) { return 0; }
      @Override public int textByteLengthAt(int column) { return overlong.length; }
      @Override public int textByteOffsetAt(int column) { return 0; }
      @Override public BoundedByteSource textSource(int column) {
        return new BoundedByteSource() {
          @Override public int length() { return overlong.length; }
          @Override public byte getByte(int index) { return overlong[index]; }
        };
      }
      @Override public int copyTextChars(int column, char[] target, int offset) {
        throw new AssertionError("invalid text key was decoded");
      }
    };
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.descriptorRows().delete(pin, forged));
    assertEquals(0, session.indexedSession().pendingMutationCount());
    StoredTableRowView fetched = new StoredTableRowView();
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(pin, valid, fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, pin.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void foreignPublishedPinCannotAccessRowsDespiteCollidingIds(@TempDir Path root)
      throws java.io.IOException {
    RelationalDatabaseOpenResult firstOpen = new RelationalDatabaseOpenResult();
    RelationalDatabaseOpenResult secondOpen = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8),
        Files.createDirectory(root.resolve("first")),
        DATABASE, GENERATION, 8, firstOpen));
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8),
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
    SqlMutationValues values = values(41, NULL_ORDINALS);
    StoredTableRowView fetched = emptyValues();
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
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK,
        database.services().descriptors().create(
            wideDescriptor(), table, detail), detail.toString());
    long objectId = table.tableId();

    RelationalSession session = session(database);
    SqlMutationValues input = values(10, NULL_ORDINALS);
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

    StoredTableRowView fetched = emptyValues();
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
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
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
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
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

    SqlMutationValues primaryValues = values(77, NULL_ORDINALS);
    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    assertEquals(StatusCode.OK, encoder.encodePhysical(
        table.descriptor().primaryKey(), primaryValues, inserted.logicalRowId()));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.indexedSession().preflightTupleMutations(1, 0, encoder.length()));
    assertEquals(StatusCode.OK, session.indexedSession().protectTupleKeyForWrite(
        table.descriptor().primaryKey().keyId(), encoder.bytes(), 0, encoder.length()));
    assertEquals(StatusCode.OK, session.indexedSession().appendTupleMutation(
        IndexedRelationalMutation.TUPLE_DELETE,
        table.tableId(), table.descriptor().primaryKey().keyId(),
        table.descriptor().primaryKey().keyId(), table.descriptor().primaryKey().shape(),
        inserted.logicalRowId(), encoder.bytes(), 0, encoder.length()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CORRUPTION, session.descriptorRows().fetchByLogicalRowId(
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
    StoredTableRowView surviving = emptyValues();
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 88, surviving));
    assertEquals(88, surviving.valueAt(0));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void scanViewReadsWideRowWithoutCallerValueLanes(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        wideDescriptor(), table, detail), detail.toString());
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
    StoredTableRowView destination = new StoredTableRowView();
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
  void indexedScanReadsPendingWideRow(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        wideDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, values(41, NULL_ORDINALS), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().update(
        table, 41, values(41, new int[] {8, 64, 255})));
    SqlMutationValues key = new SqlMutationValues();
    assertEquals(StatusCode.OK, key.reserve(table.descriptor(), 0));
    assertEquals(StatusCode.OK, key.begin(table.descriptor(), null));
    assertEquals(StatusCode.OK, key.setFixed(0, SqlTypeDescriptor.BIGINT, 41));
    RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
    assertEquals(StatusCode.OK, bounds.set(
        table.descriptor().primaryKey(), key, 1, true, key, 1, true,
        TupleBTreeScanBounds.FORWARD));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    assertEquals(StatusCode.OK, session.descriptorRows().beginIndexScan(
        table, bounds, LockMode.SHARED, cursor));
    StoredTableRowView destination = emptyValues();
    StoredTableColumnSelection selected = new StoredTableColumnSelection();
    assertEquals(StatusCode.OK, selected.selectNone(COLUMN_COUNT));
    selected.select(63);
    selected.select(255);
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(
        cursor, destination, new RelationalRowIdentityResult(), null, selected));
    assertEquals(41, destination.valueAt(0));
    assertEquals(1, destination.valueAt(63));
    assertTrue(destination.isNull(255));
    assertEquals(0, destination.descriptorAt(7));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void projectedScanRetainsFilterColumn(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, indexedPayloadValues(41, 7, 55), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, indexedPayloadValues(42, 7, 66), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    assertEquals(StatusCode.OK, session.descriptorRows().beginScan(table, cursor));
    StoredTableColumnSelection selected = new StoredTableColumnSelection();
    assertEquals(StatusCode.OK, selected.selectNone(3));
    selected.select(0);
    StoredTableRowIntegerFilter filter = new StoredTableRowIntegerFilter();
    assertEquals(StatusCode.OK, filter.configure(2, SqlComparison.EQUAL, 66));
    StoredTableRowView destination = new StoredTableRowView();
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(
        cursor, destination, new RelationalRowIdentityResult(), filter, selected));
    assertEquals(42, destination.valueAt(0));
    assertEquals(66, destination.valueAt(2));
    assertEquals(0, destination.descriptorAt(1));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().nextScan(
        cursor, destination, new RelationalRowIdentityResult(), filter, selected));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void groupedMutationsGrowBeyondDefaultWriteCounts(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
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
        index < FORMER_DEFAULT_WRITE_ENTRIES - 1;
        index++) {
      oneByte.position(0);
      assertEquals(StatusCode.OK, session.indexedSession().insert(10, index, oneByte));
    }
    RelationalRowIdentityResult accepted = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        session.descriptorRows().insert(table, values(51, NULL_ORDINALS), accepted));
    assertEquals(1, accepted.logicalRowId());
    assertEquals(
        FORMER_DEFAULT_WRITE_ENTRIES - 1,
        session.indexedSession().pendingMutationCount());
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    for (int index = 0; index < 382; index++) {
      oneByte.position(0);
      assertEquals(StatusCode.OK, session.indexedSession().insert(11, index, oneByte));
    }
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 51, values(52, NULL_ORDINALS)));
    assertEquals(382, session.indexedSession().pendingMutationCount());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        objectId, table, new StatusDetail(128)));
    session = session(database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    StoredTableRowView fetched = emptyValues();
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 51, fetched));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 52, fetched));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void updateStagesPrimaryValueAndChangedSecondaryKey(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    long tableId = table.tableId();
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, indexedPayloadValues(1, 10, 100), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    IndexedTupleIndexState primaryState = new IndexedTupleIndexState();
    IndexedTupleIndexState secondaryState = new IndexedTupleIndexState();
    IndexedTupleIndexState identityState = new IndexedTupleIndexState();
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().identityKey().keyId(), identityState));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().primaryKey().keyId(), primaryState));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().secondaryKeyAt(0).keyId(), secondaryState));
    long primaryMembership = primaryState.membershipSequence();
    long secondaryMembership = secondaryState.membershipSequence();
    long identityGeneration = identityState.generation();
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 1, indexedPayloadValues(1, 10, 101)));
    assertEquals(1, session.indexedSession().pendingTupleMutationCount());
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().primaryKey().keyId(), primaryState));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().secondaryKeyAt(0).keyId(), secondaryState));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().identityKey().keyId(), identityState));
    assertEquals(primaryMembership, primaryState.membershipSequence());
    assertEquals(secondaryMembership, secondaryState.membershipSequence());
    assertEquals(identityGeneration, identityState.generation());
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 1, indexedPayloadValues(1, 11, 102)));
    assertEquals(3, session.indexedSession().pendingTupleMutationCount());
    assertEquals(StatusCode.OK, session.commit(outcome));

    StoredTableRowView fetched = emptyValues();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().primaryKey().keyId(), primaryState));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().secondaryKeyAt(0).keyId(), secondaryState));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().identityKey().keyId(), identityState));
    assertEquals(primaryMembership, primaryState.membershipSequence());
    assertTrue(secondaryState.membershipSequence() > secondaryMembership);
    assertEquals(identityGeneration, identityState.generation());
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, fetched));
    assertEquals(11, fetched.valueAt(1));
    assertEquals(102, fetched.valueAt(2));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, table, new StatusDetail(128)));
    session = session(database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().primaryKey().keyId(), primaryState));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().secondaryKeyAt(0).keyId(), secondaryState));
    assertEquals(StatusCode.OK, session.indexedSession().readTupleIndexState(
        table.descriptor().identityKey().keyId(), identityState));
    assertEquals(primaryMembership, primaryState.membershipSequence());
    assertTrue(secondaryState.membershipSequence() > secondaryMembership);
    assertEquals(identityGeneration, identityState.generation());
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, fetched));
    assertEquals(102, fetched.valueAt(2));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void explicitTransactionRollsBackPartialBatchThenCommitsAndReopens(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
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
    SqlMutationValues batchFirst = values(201, NULL_ORDINALS);
    SqlMutationValues batchSecond = values(202, NULL_ORDINALS);
    assertEquals(StatusCode.OK, inserts.begin(batch, table, 2));
    RelationalRowIdentityResult staged = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, inserts.insert(batch, table, batchFirst, staged));
    assertEquals(StatusCode.OK, inserts.insert(
        batch, table, batchSecond, new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(statement));
    batch.reset();
    StoredTableRowView fetched = emptyValues();
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
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
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
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
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
    StoredTableRowView fetched = emptyValues();
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
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
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
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
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
        index < FORMER_DEFAULT_WRITE_ENTRIES - 1;
        index++) {
      oneByte.position(0);
      assertEquals(StatusCode.OK, session.indexedSession().insert(20, index, oneByte));
    }
    assertEquals(StatusCode.OK, inserts.begin(batch, table, 2));
    assertEquals(StatusCode.OK,
        inserts.insert(batch, table, values(81, NULL_ORDINALS), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK,
        inserts.insert(batch, table, values(82, NULL_ORDINALS), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.abort(outcome));

    batch.reset();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, inserts.begin(batch, table, 2));
    SqlMutationValues first = values(81, NULL_ORDINALS);
    SqlMutationValues second = values(82, NULL_ORDINALS);
    RelationalRowIdentityResult firstId = new RelationalRowIdentityResult();
    RelationalRowIdentityResult secondId = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        inserts.insert(batch, table, first, firstId));
    assertEquals(StatusCode.OK,
        inserts.insert(batch, table, second, secondId));
    assertEquals(3, firstId.logicalRowId());
    assertEquals(4, secondId.logicalRowId());
    assertEquals(StatusCode.OK, session.commit(outcome));
    batch.reset();
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void insertBatchRejectsReuseAfterStatementCompletion(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        textDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    RelationalDescriptorBatchInsert inserts = session.descriptorRows().batchInsert();
    RelationalDescriptorInsertBatch batch = new RelationalDescriptorInsertBatch();
    TransactionOutcome outcome = new TransactionOutcome();
    SqlMutationValues admitted = textValues(91, "a");

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, inserts.begin(batch, table, 1));
    assertEquals(StatusCode.OK, inserts.insert(batch, table, admitted,
        new RelationalRowIdentityResult()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, inserts.insert(
        batch, table, textValues(91, "a larger admitted payload"),
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

  @Test
  void embeddedDescriptorInsertRejectsUnadmittedText(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        textDescriptor(), table, new StatusDetail(128)));
    SqlMutationValues values = new SqlMutationValues();
    assertEquals(StatusCode.OK, values.reserve(table.descriptor(), 8));
    assertEquals(StatusCode.OK, values.begin(table.descriptor(), null));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, 1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        values.setTextBytes(1, SqlTypeDescriptor.varchar(64),
            ByteBuffer.wrap(new byte[] {(byte) 0xc0}), 0, 1));

    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.descriptorRows().insert(table, values, new RelationalRowIdentityResult()));
    assertEquals(0, session.indexedSession().pendingMutationCount());
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void publicScanRejectsOutOfRangeIntegerFilter(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        textDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, textValues(1, "safe"), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    assertEquals(StatusCode.OK, session.descriptorRows().beginScan(table, cursor));
    StoredTableColumnSelection oversized = new StoredTableColumnSelection();
    assertEquals(StatusCode.OK, oversized.selectNone(3));
    oversized.selectAll();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.descriptorRows().nextScan(
        cursor, new StoredTableRowView(), new RelationalRowIdentityResult(), null, oversized));
    StoredTableRowIntegerFilter filter = new StoredTableRowIntegerFilter();
    assertEquals(StatusCode.OK, filter.configure(2, SqlComparison.EQUAL, 1));
    StoredTableRowView output = new StoredTableRowView();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.descriptorRows().nextScan(
        cursor, output, new RelationalRowIdentityResult(), filter));
    assertEquals(0, output.count());
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  private static RelationalSession session(RelationalDatabase database) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    return opened.session();
  }

  private static SqlMutationValues values(long key, int[] nullOrdinals) {
    SqlMutationValues values = new SqlMutationValues();
    TableDescriptor table = wideDescriptor();
    assertEquals(StatusCode.OK, values.reserve(table, 0));
    assertEquals(StatusCode.OK, values.begin(table, null));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, key));
    for (int ordinal = 1; ordinal < COLUMN_COUNT; ordinal++) {
      assertEquals(StatusCode.OK, contains(nullOrdinals, ordinal)
          ? values.setNull(ordinal, SqlTypeDescriptor.BOOLEAN)
          : values.setFixed(ordinal, SqlTypeDescriptor.BOOLEAN, ordinal & 1));
    }
    return values;
  }

  private static StoredTableRowView emptyValues() { return new StoredTableRowView(); }

  private static void assertWideValues(
      SqlValueAccess values, long key, int[] nullOrdinals) {
    assertEquals(COLUMN_COUNT, values.count());
    assertEquals(key, values.valueAt(0));
    assertFalse(values.isNull(0));
    for (int ordinal : new int[] {7, 8, 63, 64, 255, 1_023}) {
      assertEquals(contains(nullOrdinals, ordinal), values.isNull(ordinal));
      if (!values.isNull(ordinal)) assertEquals(ordinal & 1, values.valueAt(ordinal));
    }
  }

  @Test
  void primaryScanSeesLaterOwnUpdatesAndDeletes(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    for (int id = 1; id <= 3; id++) {
      assertEquals(StatusCode.OK, session.descriptorRows().insert(
          table, indexedPayloadValues(id, 10, id * 100),
          new RelationalRowIdentityResult()));
    }
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    SchemaPin writerPin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        table.tableId(), writerPin, new StatusDetail(128)));
    IndexedSavepoint cursorSavepoint = new IndexedSavepoint();
    assertEquals(StatusCode.OK, session.createSavepoint(cursorSavepoint));
    RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
    assertEquals(StatusCode.OK, bounds.set(
        table.descriptor().primaryKey(), null, 0, true, null, 0, true,
        TupleBTreeScanBounds.FORWARD));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    assertEquals(StatusCode.OK, session.descriptorRows().beginIndexScan(
        table, bounds, LockMode.SHARED, cursor));
    StoredTableRowView fetched = emptyValues();
    RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(cursor, fetched, identity));
    assertEquals(1, fetched.valueAt(0));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(writerPin, 2, indexedPayloadValues(2, 10, 201)));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(cursorSavepoint));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(writerPin, 2, indexedPayloadValues(2, 10, 202)));
    assertEquals(StatusCode.OK, session.descriptorRows().delete(writerPin, 3));
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(cursor, fetched, identity));
    assertEquals(2, fetched.valueAt(0));
    assertEquals(202, fetched.valueAt(2));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().nextScan(cursor, fetched, identity));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, writerPin.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void pointViewsRetainPinnedGenerationsAndCloseAtTransactionEnd(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, indexedPayloadValues(1, 10, 100), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, indexedPayloadValues(2, 11, 200), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    StoredTableRowView first = emptyValues();
    StoredTableRowView second = emptyValues();
    StoredTableRowView current = emptyValues();
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, first));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 2, second));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 1, indexedPayloadValues(1, 10, 101)));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, current));
    assertEquals(100, first.valueAt(2));
    assertEquals(200, second.valueAt(2));
    assertEquals(101, current.valueAt(2));

    RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
    assertEquals(StatusCode.OK, bounds.set(
        table.descriptor().secondaryKeyAt(0), null, 0, true, null, 0, true,
        TupleBTreeScanBounds.FORWARD));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    SchemaPin scanPin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        table.tableId(), scanPin, new StatusDetail(128)));
    assertEquals(StatusCode.OK, session.descriptorRows().beginIndexScan(
        scanPin, bounds, LockMode.SHARED, cursor));
    StoredTableRowView secondary = emptyValues();
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(
        cursor, secondary, new RelationalRowIdentityResult()));
    assertEquals(101, secondary.valueAt(2));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(0, secondary.count());

    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(0, first.count());
    assertEquals(0, second.count());
    assertEquals(0, current.count());
    assertEquals(0, secondary.count());
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, first));
    assertEquals(101, first.valueAt(2));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void openScanRefreshesPendingKeyMoveInBothDirections(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    for (int id = 1; id <= 3; id++) {
      assertEquals(StatusCode.OK, session.descriptorRows().insert(
          table, indexedPayloadValues(id, 10, id * 100),
          new RelationalRowIdentityResult()));
    }
    assertEquals(StatusCode.OK, session.commit(outcome));

    for (int direction : new int[] {
        TupleBTreeScanBounds.FORWARD, TupleBTreeScanBounds.REVERSE}) {
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, session.descriptorRows().update(
          table, 2, indexedPayloadValues(2, 10, 201)));
      RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
      assertEquals(StatusCode.OK, bounds.set(
          table.descriptor().primaryKey(), indexedPayloadValues(1, 10, 100), 1, true,
          indexedPayloadValues(3, 10, 300), 1, true, direction));
      RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
      SchemaPin scanPin = new SchemaPin();
      assertEquals(StatusCode.OK, database.services().descriptors().open(
          table.tableId(), scanPin, new StatusDetail(128)));
      assertEquals(StatusCode.OK, session.descriptorRows().beginIndexScan(
          scanPin, bounds, LockMode.SHARED, cursor));
      StoredTableRowView row = emptyValues();
      RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
      assertEquals(StatusCode.OK, session.descriptorRows().nextScan(cursor, row, identity));
      assertEquals(direction == TupleBTreeScanBounds.FORWARD ? 1 : 3, row.valueAt(0));
      assertEquals(StatusCode.OK, session.descriptorRows().update(
          table, 2, indexedPayloadValues(4, 10, 204)));
      assertEquals(StatusCode.OK, session.descriptorRows().nextScan(cursor, row, identity));
      assertEquals(direction == TupleBTreeScanBounds.FORWARD ? 3 : 1, row.valueAt(0));
      assertEquals(StatusCode.CONFLICT,
          session.descriptorRows().nextScan(cursor, row, identity));
      assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
      assertEquals(StatusCode.OK, session.abort(outcome));
    }
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void hiddenPrimaryAndSecondaryReadCanonicalRowsAfterReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        hiddenPayloadDescriptor(), table, new StatusDetail(128)));
    long tableId = table.tableId();
    assertEquals(null, table.descriptor().primaryKey());
    assertEquals(io.riverdb.format.catalog.CatalogKeyspace.relationalIdentityKeyId(tableId),
        table.descriptor().clusteredKey().keyId());
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, hiddenPayloadValues(7, 20), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, hiddenPayloadValues(7, 10), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, table, new StatusDetail(128)));
    session = session(database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    RelationalDescriptorScanCursor full = new RelationalDescriptorScanCursor();
    SchemaPin fullPin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, fullPin, new StatusDetail(128)));
    assertEquals(StatusCode.OK, session.descriptorRows().beginScan(fullPin, full));
    StoredTableRowView row = emptyValues();
    RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(full, row, identity));
    assertEquals(20, row.valueAt(1));
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(full, row, identity));
    assertEquals(10, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().nextScan(full, row, identity));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(full));

    RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
    assertEquals(StatusCode.OK, bounds.set(
        table.descriptor().secondaryKeyAt(0), null, 0, true, null, 0, true,
        TupleBTreeScanBounds.FORWARD));
    RelationalDescriptorScanCursor secondary = new RelationalDescriptorScanCursor();
    SchemaPin secondaryPin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, secondaryPin, new StatusDetail(128)));
    assertEquals(StatusCode.OK, session.descriptorRows().beginIndexScan(
        secondaryPin, bounds, LockMode.SHARED, secondary));
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(secondary, row, identity));
    assertEquals(10, row.valueAt(1));
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(secondary, row, identity));
    assertEquals(20, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().nextScan(secondary, row, identity));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(secondary));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void identityLocatorTracksPrimaryMoveAtEachSnapshot(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    long tableId = table.tableId();
    RelationalSession writer = session(database);
    RelationalSession reader = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    RelationalRowIdentityResult inserted = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.descriptorRows().insert(
        table, indexedPayloadValues(1, 10, 100), inserted));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    long rowId = inserted.logicalRowId();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.descriptorRows().update(
        table, 1, indexedPayloadValues(2, 10, 101)));
    assertEquals(4, writer.indexedSession().pendingTupleMutationCount());
    StoredTableRowView pending = emptyValues();
    assertEquals(StatusCode.OK, writer.descriptorRows().fetchByLogicalRowId(
        table, rowId, pending));
    assertEquals(2, pending.valueAt(0));
    assertEquals(StatusCode.OK, writer.commit(outcome));

    StoredTableRowView old = emptyValues();
    assertEquals(StatusCode.OK, reader.descriptorRows().fetchByLogicalRowId(
        table, rowId, old));
    assertEquals(1, old.valueAt(0));
    assertEquals(100, old.valueAt(2));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reader.descriptorRows().fetchByLogicalRowId(
        table, rowId, old));
    assertEquals(2, old.valueAt(0));
    assertEquals(101, old.valueAt(2));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, table, new StatusDetail(128)));
    reader = session(database);
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reader.descriptorRows().fetchByLogicalRowId(
        table, rowId, old));
    assertEquals(2, old.valueAt(0));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void primaryAndSecondaryReadsDoNotConsultIdentityLocator(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    RelationalRowIdentityResult inserted = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, indexedPayloadValues(1, 10, 100), inserted));
    assertEquals(StatusCode.OK, session.commit(outcome));

    long rowId = inserted.logicalRowId();
    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    assertEquals(StatusCode.OK, encoder.encodePhysical(
        table.descriptor().identityKey(), null, rowId));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.indexedSession().preflightTupleMutations(1, 1, encoder.length()));
    assertEquals(StatusCode.OK, session.indexedSession().protectTupleKeyForWrite(
        table.descriptor().identityKey().keyId(), encoder.bytes(), 0, encoder.length()));
    assertEquals(StatusCode.OK, session.indexedSession().appendTupleMutation(
        io.riverdb.engine.table.IndexedRelationalMutation.TUPLE_DELETE,
        table.tableId(), table.descriptor().identityKey().keyId(),
        table.descriptor().identityKey().keyId(), table.descriptor().identityKey().shape(),
        rowId, encoder.bytes(), 0, encoder.length()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    StoredTableRowView row = emptyValues();
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, row));
    assertEquals(100, row.valueAt(2));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().fetchByLogicalRowId(table, rowId, row));
    RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
    assertEquals(StatusCode.OK, bounds.set(
        table.descriptor().secondaryKeyAt(0), null, 0, true, null, 0, true,
        TupleBTreeScanBounds.FORWARD));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    SchemaPin scanPin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        table.tableId(), scanPin, new StatusDetail(128)));
    assertEquals(StatusCode.OK, session.descriptorRows().beginIndexScan(
        scanPin, bounds, LockMode.SHARED, cursor));
    assertEquals(StatusCode.OK, session.descriptorRows().nextScan(
        cursor, row, new RelationalRowIdentityResult()));
    assertEquals(100, row.valueAt(2));
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().update(
        table, 1, indexedPayloadValues(1, 10, 101)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, row));
    assertEquals(101, row.valueAt(2));
    assertEquals(StatusCode.CONFLICT,
        session.descriptorRows().fetchByLogicalRowId(table, rowId, row));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void lockedSecondaryCandidateFollowsMovedPrimaryThroughIdentity(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    RelationalSession reader = session(database);
    RelationalSession writer = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.descriptorRows().insert(
        table, indexedPayloadValues(1, 10, 100), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, writer.commit(outcome));

    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    RelationalDescriptorIndexBounds bounds = new RelationalDescriptorIndexBounds();
    assertEquals(StatusCode.OK, bounds.set(
        table.descriptor().secondaryKeyAt(0), null, 0, true, null, 0, true,
        TupleBTreeScanBounds.FORWARD));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    SchemaPin scanPin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        table.tableId(), scanPin, new StatusDetail(128)));
    assertEquals(StatusCode.OK, reader.descriptorRows().beginIndexScan(
        scanPin, bounds, LockMode.SHARED, cursor));
    StoredTableRowView old = emptyValues();
    assertEquals(StatusCode.OK, reader.descriptorRows().nextScan(
        cursor, old, new RelationalRowIdentityResult()));
    assertEquals(1, old.valueAt(0));

    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.descriptorRows().update(
        table, 1, indexedPayloadValues(2, 10, 101)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    StoredTableRowView current = emptyValues();
    RelationalLockedCandidateResult locked = new RelationalLockedCandidateResult();
    assertEquals(StatusCode.OK, reader.descriptorRows().lockScannedCandidate(
        cursor, current, locked));
    assertTrue(locked.isLocked());
    assertEquals(2, current.valueAt(0));
    assertEquals(101, current.valueAt(2));
    assertEquals(StatusCode.OK, reader.descriptorRows().releaseCurrent());
    assertEquals(StatusCode.OK, reader.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void overflowRowSurvivesReopenAndOldSnapshotAcrossInlineReplacement(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        overflowDescriptor(), table, new StatusDetail(128)));
    long tableId = table.tableId();
    String key = "😀".repeat(765);
    String large = "🧱".repeat(3000);
    SqlMutationValues original = overflowValues(table.descriptor(), key, large);
    RelationalSession writer = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.descriptorRows().insert(
        table, original, new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, table, new StatusDetail(128)));
    RelationalSession reader = session(database);
    writer = session(database);
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    RelationalDescriptorScanCursor held = new RelationalDescriptorScanCursor();
    SchemaPin heldPin = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, heldPin, new StatusDetail(128)));
    assertEquals(StatusCode.OK, reader.descriptorRows().beginScan(heldPin, held));

    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.descriptorRows().update(
        table, original, overflowValues(table.descriptor(), key, "small")));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    StoredTableRowView old = emptyValues();
    assertEquals(StatusCode.OK, reader.descriptorRows().nextScan(
        held, old, new RelationalRowIdentityResult()));
    assertEquals(12_000, old.textByteLengthAt(1));
    assertTrue(held.tupleRow().overflowPageId() > 0);
    assertEquals(StatusCode.OK, reader.descriptorRows().closeScan(held));
    assertEquals(StatusCode.OK, reader.descriptorRows().fetch(table, original, old));
    assertEquals(12_000, old.textByteLengthAt(1));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reader.descriptorRows().fetch(table, original, old));
    assertEquals(5, old.textByteLengthAt(1));
    assertEquals(0, old.pointRow().overflowPageId());
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void keyMoveBackThenValueReplacementRetainsTheRow(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        indexedPayloadDescriptor(), table, new StatusDetail(128)));
    long tableId = table.tableId();
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, indexedPayloadValues(1, 10, 100), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 1, indexedPayloadValues(2, 10, 101)));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 2, indexedPayloadValues(1, 10, 102)));
    IndexedSavepoint savepoint = new IndexedSavepoint();
    assertEquals(StatusCode.OK, session.createSavepoint(savepoint));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 1, indexedPayloadValues(1, 10, 103)));
    StoredTableRowView fetched = emptyValues();
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, fetched));
    assertEquals(103, fetched.valueAt(2));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(savepoint));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, fetched));
    assertEquals(102, fetched.valueAt(2));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 1, indexedPayloadValues(1, 10, 104)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, table, new StatusDetail(128)));
    session = session(database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT, session.descriptorRows().fetch(table, 2, fetched));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 1, fetched));
    assertEquals(104, fetched.valueAt(2));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void overflowPrimaryValueSurvivesReplacementAndReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().create(
        overflowTextDescriptor(), table, new StatusDetail(128)));
    long tableId = table.tableId();
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    String large = "😀".repeat(3_400);

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(
        table, overflowTextValues(7, large), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    StoredTableRowView fetched = emptyValues();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 7, fetched));
    assertEquals(13_600, fetched.textByteLengthAt(1));
    assertEquals((byte) 0xf0, fetched.getByte(fetched.textByteOffsetAt(1)));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.descriptorRows().update(table, 7, overflowTextValues(7, "small")));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    table = new SchemaPin();
    assertEquals(StatusCode.OK, database.services().descriptors().open(
        tableId, table, new StatusDetail(128)));
    session = session(database);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.descriptorRows().fetch(table, 7, fetched));
    assertEquals(5, fetched.textByteLengthAt(1));
    assertEquals((byte) 's', fetched.getByte(fetched.textByteOffsetAt(1)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.release());
    assertEquals(StatusCode.OK, database.close());
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

  private static TableDescriptor overflowTextDescriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(4_000)},
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

  private static SqlMutationValues overflowTextValues(long key, String text) {
    SqlMutationValues values = new SqlMutationValues();
    TableDescriptor table = overflowTextDescriptor();
    assertEquals(StatusCode.OK, values.reserve(table, text.length() * 2));
    assertEquals(StatusCode.OK, values.begin(table, null));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, key));
    assertEquals(StatusCode.OK,
        values.setText(1, SqlTypeDescriptor.varchar(4_000), text));
    return values;
  }

  private static TableDescriptor textPrimaryDescriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.varchar(8)}, new CharSequence[] {"id"},
        new boolean[] {false}, columns));
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

  private static TableDescriptor hiddenPayloadDescriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"value", "indexed_value"},
        new boolean[] {false, false}, columns));
    KeyDescriptor.Result secondary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
        1, KeyDescriptor.KIND_SECONDARY, false, columns.value(), new int[] {1},
        0, "by_indexed_value", secondary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        1, 1, 1, columns.value(), null,
        new KeyDescriptor[] {secondary.value()}, null, table, null));
    return table.value();
  }

  private static TableDescriptor overflowDescriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.varchar(765), SqlTypeDescriptor.varchar(3000)},
        new CharSequence[] {"id", "payload"}, new boolean[] {false, false}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        1, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        1, 1, 1, columns.value(), primary.value(), null, null, table, null));
    return table.value();
  }

  private static SqlMutationValues overflowValues(
      TableDescriptor table, String key, String payload) {
    SqlMutationValues values = new SqlMutationValues();
    assertEquals(StatusCode.OK, values.reserve(table, 16_000));
    assertEquals(StatusCode.OK, values.begin(table, null));
    assertEquals(StatusCode.OK,
        values.setText(0, SqlTypeDescriptor.varchar(765), key));
    assertEquals(StatusCode.OK,
        values.setText(1, SqlTypeDescriptor.varchar(3000), payload));
    return values;
  }

  private static SqlMutationValues hiddenPayloadValues(long value, long indexedValue) {
    TableDescriptor table = hiddenPayloadDescriptor();
    SqlMutationValues values = new SqlMutationValues();
    assertEquals(StatusCode.OK, values.reserve(table, 0));
    assertEquals(StatusCode.OK, values.begin(table, null));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, values.setFixed(1, SqlTypeDescriptor.BIGINT, indexedValue));
    return values;
  }

  private static SqlMutationValues indexedPayloadValues(
      long id, long indexedValue, long payload) {
    SqlMutationValues values = new SqlMutationValues();
    TableDescriptor table = indexedPayloadDescriptor();
    assertEquals(StatusCode.OK, values.reserve(table, 0));
    assertEquals(StatusCode.OK, values.begin(table, null));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, id));
    assertEquals(StatusCode.OK,
        values.setFixed(1, SqlTypeDescriptor.BIGINT, indexedValue));
    assertEquals(StatusCode.OK,
        values.setFixed(2, SqlTypeDescriptor.BIGINT, payload));
    return values;
  }

  private static SqlMutationValues textValues(long key, CharSequence text) {
    SqlMutationValues values = new SqlMutationValues();
    TableDescriptor table = textDescriptor();
    assertEquals(StatusCode.OK, values.reserve(table, 256));
    assertEquals(StatusCode.OK, values.begin(table, null));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, key));
    assertEquals(StatusCode.OK,
        values.setText(1, SqlTypeDescriptor.varchar(64), text));
    return values;
  }
}
