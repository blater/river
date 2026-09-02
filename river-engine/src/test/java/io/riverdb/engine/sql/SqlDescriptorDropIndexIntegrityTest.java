package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorDropIndexIntegrityTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x44524f50494e5445L, 0x4752495459303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void protectsCompositeNumericForeignKeyRootsAndConstraintKeys(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE parents (id INTEGER,tenant INTEGER,amount DECIMAL(22,18),"
            + "ratio DOUBLE PRECISION,CONSTRAINT parents_pk PRIMARY KEY(id))",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX parent_lookup ON parents(tenant,amount,ratio)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX parent_scan ON parents(amount,ratio)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE children (id INTEGER,tenant INTEGER,amount DECIMAL(22,18),"
            + "ratio DOUBLE PRECISION,PRIMARY KEY(id),"
            + "CONSTRAINT child_parent FOREIGN KEY(tenant,amount,ratio) "
            + "REFERENCES parents(tenant,amount,ratio))",
        result));

    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DROP INDEX parent_lookup ON parents", result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("DROP INDEX parents_pk ON parents", result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("DROP INDEX PRIMARY ON parents", result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("DROP INDEX _river_fk_1 ON children", result));
    assertEquals(StatusCode.OK,
        session.execute("DROP INDEX parent_scan ON parents", result));

    assertEquals(StatusCode.OK, session.execute("DROP TABLE children", result));
    assertEquals(StatusCode.OK,
        session.execute("DROP INDEX parent_lookup ON parents", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rejectsNamedUniqueConstraintWithoutForeignReferences(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE constrained (id INTEGER,amount DECIMAL(38,38),"
            + "CONSTRAINT constrained_pk PRIMARY KEY(id),"
            + "CONSTRAINT constrained_amount UNIQUE(amount))",
        result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("DROP INDEX constrained_amount ON constrained", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void localForeignKeyRequiresExactOrderedCompositeSupport() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {
            SqlTypeDescriptor.INTEGER,
            SqlTypeDescriptor.decimal(22, 18),
            SqlTypeDescriptor.DOUBLE
        },
        new CharSequence[] {"tenant", "amount", "ratio"},
        new boolean[3], columns));
    KeyDescriptor foreign = key(
        columns.value(), KeyDescriptor.KIND_FOREIGN, false,
        new int[] {0, 1, 2}, 97, "child_parent");
    KeyDescriptor exact = key(
        columns.value(), KeyDescriptor.KIND_SECONDARY, false,
        new int[] {0, 1, 2}, 0, "user_support");
    KeyDescriptor reordered = key(
        columns.value(), KeyDescriptor.KIND_SECONDARY, false,
        new int[] {0, 2, 1}, 0, "wrong_order");

    assertTrue(SqlDescriptorIndexDependencies.supportsLocalForeignKeys(
        table(columns.value(), exact, foreign)));
    assertFalse(SqlDescriptorIndexDependencies.supportsLocalForeignKeys(
        table(columns.value(), reordered, foreign)));
    assertFalse(SqlDescriptorIndexDependencies.supportsLocalForeignKeys(
        table(columns.value(), null, foreign)));
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }

  private static KeyDescriptor key(
      ColumnDescriptorSet columns, int kind, boolean unique, int[] parts,
      long referenced, CharSequence name) {
    KeyDescriptor.Result key = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamedUnbound(
        kind, unique, columns, parts, referenced, name, key, null));
    return key.value();
  }

  private static TableDescriptor table(
      ColumnDescriptorSet columns, KeyDescriptor support, KeyDescriptor foreign) {
    TableDescriptor.Result table = new TableDescriptor.Result();
    KeyDescriptor[] secondary = support == null ? null : new KeyDescriptor[] {support};
    assertEquals(StatusCode.OK, TableDescriptor.createForTest(
        columns, null, secondary, new KeyDescriptor[] {foreign}, table));
    return table.value();
  }
}
