package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedRelationalMutation;
import io.riverdb.format.FormatBytes;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalDescriptorStorageValidationTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x53544f524556414cL, 0x49444154494f4e31L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void missingPrimaryMappingIsCorruptionOnReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    NamedTable table = createNamed(database, "missing_map");
    long logicalRowId = insert(database, table.pin, 41);
    deletePrimaryTuple(database, table.pin, 41, logicalRowId);
    close(database, table.pin);

    assertCorruptReopen(root);
  }

  @Test
  void missingHistoricalLayoutAndCorruptBaseHeaderFailReopen(@TempDir Path root) {
    Path layoutRoot = root.resolve("layout");
    RelationalDatabase layoutDatabase = create(layoutRoot);
    NamedTable layoutTable = createNamed(layoutDatabase, "missing_layout");
    long layoutRow = insert(layoutDatabase, layoutTable.pin, 81);
    ByteBuffer bytes = baseRow(layoutDatabase, layoutTable.objectId, layoutRow);
    FormatBytes.putLong(bytes, 16, layoutTable.pin.rowLayoutId() + 10_000);
    mutate(layoutDatabase, RelationalDescriptorKeyspace.baseRows(layoutTable.objectId),
        layoutRow, bytes);
    close(layoutDatabase, layoutTable.pin);
    assertCorruptReopen(layoutRoot);

    Path headerRoot = root.resolve("header");
    RelationalDatabase headerDatabase = create(headerRoot);
    NamedTable headerTable = createNamed(headerDatabase, "bad_header");
    long headerRow = insert(headerDatabase, headerTable.pin, 82);
    bytes = baseRow(headerDatabase, headerTable.objectId, headerRow);
    bytes.put(0, (byte) (bytes.get(0) ^ 1));
    mutate(headerDatabase, RelationalDescriptorKeyspace.baseRows(headerTable.objectId),
        headerRow, bytes);
    close(headerDatabase, headerTable.pin);
    assertCorruptReopen(headerRoot);
  }

  private static RelationalDatabase create(Path root) {
    try {
      Files.createDirectories(root);
    } catch (java.io.IOException error) {
      throw new AssertionError(error);
    }
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static NamedTable createNamed(RelationalDatabase database, String name) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    SchemaPin pin = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.prepareDescriptorTable(name, descriptor(), detail), detail.toString());
    assertEquals(StatusCode.OK, session.resolveDescriptor(name, pin, detail));
    assertEquals(StatusCode.OK, session.commit(outcome));
    return new NamedTable(pin.tableId(), pin);
  }

  private static long insert(RelationalDatabase database, SchemaPin pin, long primaryKey) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(1, 1, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(1));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, primaryKey));
    RelationalRowIdentityResult result = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.descriptorRows().insert(pin, values, result));
    assertEquals(StatusCode.OK, session.commit(outcome));
    return result.logicalRowId();
  }

  private static ByteBuffer baseRow(
      RelationalDatabase database, long objectId, long logicalRowId) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.indexedSession().fetchByKey(
        RelationalDescriptorKeyspace.baseRows(objectId), logicalRowId, row));
    ByteBuffer bytes = ByteBuffer.allocate(row.length());
    assertEquals(StatusCode.OK, row.copyTo(bytes));
    bytes.flip();
    assertEquals(StatusCode.OK, session.commit(outcome));
    return bytes;
  }

  private static void mutate(
      RelationalDatabase database, long space, long key, ByteBuffer row) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    StatusCode status = row == null
        ? session.indexedSession().delete(space, key)
        : session.indexedSession().update(space, key, row);
    assertEquals(StatusCode.OK, status);
    assertEquals(StatusCode.OK, session.commit(outcome));
  }

  private static void deletePrimaryTuple(
      RelationalDatabase database, SchemaPin pin,
      long primaryKey, long logicalRowId) {
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(1, 1, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(1));
    assertEquals(StatusCode.OK,
        values.setFixed(0, SqlTypeDescriptor.BIGINT, primaryKey));
    RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
    assertEquals(StatusCode.OK,
        encoder.encodePhysical(pin.descriptor().primaryKey(), values, logicalRowId));
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.indexedSession().preflightTupleMutations(1, 1, encoder.length()));
    assertEquals(StatusCode.OK, session.indexedSession().appendTupleMutation(
        IndexedRelationalMutation.TUPLE_DELETE,
        pin.tableId(), pin.descriptor().primaryKey().keyId(),
        pin.descriptor().primaryKey().keyId(), pin.descriptor().primaryKey().shape(),
        logicalRowId, encoder.bytes(), 0, encoder.length()));
    assertEquals(StatusCode.OK, session.commit(outcome));
  }

  private static RelationalSession session(RelationalDatabase database) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    return opened.session();
  }

  private static void close(RelationalDatabase database, SchemaPin pin) {
    assertEquals(StatusCode.OK, pin.release());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertCorruptReopen(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.CORRUPTION,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
  }

  private static TableDescriptor descriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT}, new CharSequence[] {"id"},
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

  private static final class NamedTable {
    final long objectId;
    final SchemaPin pin;

    NamedTable(long id, SchemaPin schemaPin) {
      objectId = id;
      pin = schemaPin;
    }
  }
}
