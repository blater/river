package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverCatalogTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434154414c4f4731L, 0x454e47494e453031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final String TABLE = "customer_account_transaction_history";
  private static final String VIEW = "customer_account_transaction_history_view";

  @Test
  void streamsTransactionallyVisibleCatalogObjectsAndReopens(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE " + TABLE
                + " (id BIGINT PRIMARY KEY, value BIGINT, region BIGINT, pending BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX history_value ON " + TABLE + "(value)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE UNIQUE INDEX history_region ON " + TABLE + "(region)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW " + VIEW + " AS SELECT id, value FROM " + TABLE,
            command));
    assertCatalog(session, true, true, false);
    assertIndexes(session, false);
    assertColumns(
        session,
        TABLE,
        new String[] {"id", "value", "region", "pending"},
        new String[] {"BIGINT", "BIGINT", "BIGINT", "BIGINT"},
        new boolean[] {false, true, true, true});
    assertUnavailableColumns(session, VIEW);

    assertEquals(StatusCode.OK, session.execute("BEGIN", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE rolled_back_catalog_table "
                + "(id BIGINT PRIMARY KEY, value BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX rolled_back_index ON " + TABLE + "(pending)",
            command));
    assertCatalog(session, true, true, true);
    assertIndexes(session, true);
    assertColumns(
        session,
        "rolled_back_catalog_table",
        new String[] {"id", "value"},
        new String[] {"BIGINT", "BIGINT"},
        new boolean[] {false, true});
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", command));
    assertCatalog(session, true, true, false);
    assertIndexes(session, false);
    assertUnavailableColumns(session, "rolled_back_catalog_table");

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertCatalog(session, true, true, false);
    assertIndexes(session, false);
    assertColumns(
        session,
        TABLE,
        new String[] {"id", "value", "region", "pending"},
        new String[] {"BIGINT", "BIGINT", "BIGINT", "BIGINT"},
        new boolean[] {false, true, true, true});
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertCatalog(
      RiverSession session,
      boolean expectedTable,
      boolean expectedView,
      boolean expectedRolledBackTable) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery("SHOW TABLES", opened));
    RiverQuery query = opened.query();
    assertEquals(2, query.columnCount());
    assertEquals("table_name", query.columnName(0).toString());
    assertEquals("table_type", query.columnName(1).toString());
    assertTrue(query.columnIsVarchar(0));
    assertTrue(query.columnIsVarchar(1));

    boolean table = false;
    boolean view = false;
    boolean rolledBack = false;
    int objectCount = 0;
    RowResult row = new RowResult();
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) {
        break;
      }
      String name = text(row, 0);
      String type = text(row, 1);
      objectCount++;
      if (TABLE.equals(name)) {
        table = "TABLE".equals(type);
      } else if (VIEW.equals(name)) {
        view = "VIEW".equals(type);
      } else if ("rolled_back_catalog_table".equals(name)) {
        rolledBack = "TABLE".equals(type);
      }
    }
    assertEquals(expectedTable, table);
    assertEquals(expectedView, view);
    assertEquals(expectedRolledBackTable, rolledBack);
    assertEquals(expectedRolledBackTable ? 3 : 2, objectCount);
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static String text(RowResult row, int index) {
    char[] characters = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
    int length = row.copyTextAt(index, characters, 0);
    assertTrue(length >= 0);
    return new String(characters, 0, length);
  }

  private static void assertIndexes(RiverSession session, boolean expectedRolledBack) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SHOW INDEXES FROM " + TABLE, opened));
    RiverQuery query = opened.query();
    assertEquals(5, query.columnCount());
    assertEquals("index_name", query.columnName(0).toString());
    assertEquals("column_name", query.columnName(1).toString());
    assertEquals("is_unique", query.columnName(2).toString());
    assertEquals("is_primary", query.columnName(3).toString());
    assertEquals("is_constraint", query.columnName(4).toString());
    assertTrue(query.columnIsVarchar(0));
    assertTrue(query.columnIsVarchar(1));
    assertFalse(query.columnIsVarchar(2));

    boolean primary = false;
    boolean value = false;
    boolean region = false;
    boolean rolledBack = false;
    int rows = 0;
    RowResult row = new RowResult();
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) {
        break;
      }
      rows++;
      String column = text(row, 1);
      if (row.valueAt(3) == 1) {
        assertTrue(row.isNull(0));
        primary = "id".equals(column) && row.valueAt(2) == 1;
      } else {
        String name = text(row, 0);
        if ("history_value".equals(name)) {
          value = "value".equals(column) && row.valueAt(2) == 0;
        } else if ("history_region".equals(name)) {
          region = "region".equals(column) && row.valueAt(2) == 1;
        } else if ("rolled_back_index".equals(name)) {
          rolledBack = "pending".equals(column) && row.valueAt(2) == 0;
        }
      }
    }
    assertEquals(expectedRolledBack ? 4 : 3, rows);
    assertTrue(primary);
    assertTrue(value);
    assertTrue(region);
    assertEquals(expectedRolledBack, rolledBack);
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertColumns(
      RiverSession session,
      String table,
      String[] names,
      String[] typeNames,
      boolean[] nullable) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SHOW COLUMNS FROM " + table, opened));
    RiverQuery query = opened.query();
    assertEquals(4, query.columnCount());
    assertEquals("column_name", query.columnName(0).toString());
    assertEquals("type", query.columnName(1).toString());
    assertEquals("is_nullable", query.columnName(2).toString());
    assertEquals("ordinal", query.columnName(3).toString());
    assertTrue(query.columnIsVarchar(0));
    assertTrue(query.columnIsVarchar(1));
    assertFalse(query.columnIsVarchar(2));
    assertFalse(query.columnIsVarchar(3));
    assertEquals(SqlTypeDescriptor.varchar(64), query.columnTypeDescriptor(0));
    assertEquals(SqlTypeDescriptor.varchar(48), query.columnTypeDescriptor(1));
    assertEquals(SqlTypeDescriptor.BOOLEAN, query.columnTypeDescriptor(2));
    assertEquals(SqlTypeDescriptor.BIGINT, query.columnTypeDescriptor(3));
    for (int index = 0; index < query.columnCount(); index++) {
      assertFalse(query.columnIsNullable(index));
    }

    RowResult row = new RowResult();
    for (int index = 0; index < names.length; index++) {
      assertEquals(StatusCode.OK, query.next(row));
      assertTrue(row.isAvailable());
      assertEquals(names[index], text(row, 0));
      assertEquals(typeNames[index], text(row, 1));
      assertEquals(nullable[index] ? 1 : 0, row.valueAt(2));
      assertEquals(index + 1, row.valueAt(3));
      for (int column = 0; column < row.columnCount(); column++) {
        assertFalse(row.isNull(column));
        assertEquals(query.columnTypeDescriptor(column), row.typeDescriptorAt(column));
      }
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(names.length, query.rowsReturned());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertUnavailableColumns(RiverSession session, String table) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.CONFLICT,
        session.beginQuery("SHOW COLUMNS FROM " + table, opened));
    assertColumns(
        session,
        TABLE,
        new String[] {"id", "value", "region", "pending"},
        new String[] {"BIGINT", "BIGINT", "BIGINT", "BIGINT"},
        new boolean[] {false, true, true, true});
  }
}
