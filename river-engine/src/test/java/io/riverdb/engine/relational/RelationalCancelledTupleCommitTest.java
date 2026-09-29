package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

final class RelationalCancelledTupleCommitTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(491, 499);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancelledTableWorkPreservesOtherRowsAndIdentityFloor(
      boolean surviving, @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SchemaPin first = new SchemaPin();
    SchemaPin second = new SchemaPin();
    assertEquals(StatusCode.OK,
        database.services().descriptors().create(table(1), first, new StatusDetail(128)));
    assertEquals(StatusCode.OK,
        database.services().descriptors().create(table(2), second, new StatusDetail(128)));
    RelationalSession writer = session(database);
    TransactionOutcome outcome = new TransactionOutcome();
    RelationalRowIdentityResult cancelled = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, writer.descriptorRows().insert(first, value(first, 7), cancelled));
    long cancelledIdentity = cancelled.logicalRowId();
    assertEquals(StatusCode.OK, writer.descriptorRows().delete(first, 7));
    if (surviving) assertEquals(StatusCode.OK, writer.descriptorRows().insert(
        second, value(second, 8), new RelationalRowIdentityResult()));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, first.release());
    assertEquals(StatusCode.OK, second.release());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK, RelationalDatabase.openExisting(
        databaseRequest(8), root, DATABASE, GENERATION, 8,
        EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    assertEquals(StatusCode.OK,
        database.services().descriptors().open(1, first, new StatusDetail(128)));
    assertEquals(StatusCode.OK,
        database.services().descriptors().open(2, second, new StatusDetail(128)));
    writer = session(database);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.SERIALIZABLE));
    StoredTableRowView row = new StoredTableRowView();
    assertEquals(StatusCode.CONFLICT, writer.descriptorRows().fetch(first, 7, row));
    assertEquals(surviving ? StatusCode.OK : StatusCode.CONFLICT,
        writer.descriptorRows().fetch(second, 8, row));
    RelationalRowIdentityResult inserted = new RelationalRowIdentityResult();
    assertEquals(StatusCode.OK,
        writer.descriptorRows().insert(first, value(first, 9), inserted));
    assertTrue(inserted.logicalRowId() > cancelledIdentity);
    IndexedSavepoint savepoint = new IndexedSavepoint();
    assertEquals(StatusCode.OK, writer.createSavepoint(savepoint));
    assertEquals(StatusCode.OK, writer.descriptorRows().delete(first, 9));
    assertEquals(StatusCode.OK, writer.rollbackToSavepoint(savepoint));
    assertEquals(StatusCode.OK, writer.releaseSavepoint(savepoint));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, first.release());
    assertEquals(StatusCode.OK, second.release());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(StatusCode.OK, RelationalDatabase.openExisting(
        databaseRequest(8), root, DATABASE, GENERATION, 8,
        EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    assertEquals(StatusCode.OK,
        database.services().descriptors().open(1, first, new StatusDetail(128)));
    writer = session(database);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.descriptorRows().fetch(first, 9, row));
    assertEquals(9, row.valueAt(0));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, first.release());
    assertEquals(StatusCode.OK, database.close());
  }

  private static RelationalSession session(RelationalDatabase database) {
    RelationalSessionOpenResult result = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(result));
    return result.session();
  }

  private static TableDescriptor table(long id) {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT}, new CharSequence[] {"id"},
        new boolean[] {false}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(id, KeyDescriptor.KIND_PRIMARY,
        true, columns.value(), new int[] {0}, 0, primary, null));
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        id, 1, 1, columns.value(), primary.value(), null, null, result, null));
    return result.value();
  }

  private static SqlMutationValues value(SchemaPin pin, long id) {
    SqlMutationValues values = new SqlMutationValues();
    assertEquals(StatusCode.OK, values.reserve(pin.descriptor(), 0));
    assertEquals(StatusCode.OK, values.begin(pin.descriptor(), null));
    assertEquals(StatusCode.OK, values.setFixed(0, SqlTypeDescriptor.BIGINT, id));
    return values;
  }
}
