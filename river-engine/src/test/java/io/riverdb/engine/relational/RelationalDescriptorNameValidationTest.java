package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalDescriptorNameValidationTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e414d4556414c49L, 0x444154494f4e3031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void malformedCommittedNameIsCorruptionOnReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    long objectId = createNamed(database, "valid_name");
    replaceName(database, objectId, ByteBuffer.wrap(new byte[] {(byte) 0xc0}));
    assertEquals(StatusCode.OK, database.close());

    assertCorruptReopen(root);
  }

  @Test
  void orphanedCommittedNameIsCorruptionOnReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    insertName(database, 777, "orphan");
    assertEquals(StatusCode.OK, database.close());

    assertCorruptReopen(root);
  }

  @Test
  void duplicateCommittedNameIsCorruptionOnReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    createNamed(database, "duplicate");
    long secondObjectId = createNamed(database, "second");
    replaceName(database, secondObjectId,
        ByteBuffer.wrap("duplicate".getBytes(StandardCharsets.UTF_8)));
    assertEquals(StatusCode.OK, database.close());

    assertCorruptReopen(root);
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static long createNamed(RelationalDatabase database, String name) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    SchemaPin pin = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.prepareDescriptorTable(name, descriptor(), detail), detail.toString());
    assertEquals(StatusCode.OK, session.resolveDescriptor(name, pin, detail), detail.toString());
    long objectId = pin.tableId();
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, pin.release());
    return objectId;
  }

  private static void insertName(RelationalDatabase database, long objectId, String name) {
    mutateName(database, objectId,
        ByteBuffer.wrap(name.getBytes(StandardCharsets.UTF_8)), false);
  }

  private static void replaceName(
      RelationalDatabase database, long objectId, ByteBuffer bytes) {
    mutateName(database, objectId, bytes, true);
  }

  private static void mutateName(
      RelationalDatabase database, long objectId, ByteBuffer bytes, boolean update) {
    RelationalSession session = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    StatusCode status = update
        ? session.indexedSession().update(
            RelationalDescriptorKeyspace.NAME_MAP_SPACE, objectId, bytes)
        : session.indexedSession().insert(
            RelationalDescriptorKeyspace.NAME_MAP_SPACE, objectId, bytes);
    assertEquals(StatusCode.OK, status);
    assertEquals(StatusCode.OK, session.commit(outcome));
  }

  private static RelationalSession session(RelationalDatabase database) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    return opened.session();
  }

  private static void assertCorruptReopen(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.CORRUPTION,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
  }

  private static TableDescriptor descriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"id"},
        new boolean[] {false},
        columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        1, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        1, 1, 1, columns.value(), primary.value(), null, null, table, null));
    return table.value();
  }
}
