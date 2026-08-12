package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
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
            "CREATE TABLE " + TABLE + " (id BIGINT PRIMARY KEY, value BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW " + VIEW + " AS SELECT id, value FROM " + TABLE,
            command));
    assertCatalog(session, true, true, false);

    assertEquals(StatusCode.OK, session.execute("BEGIN", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE rolled_back_catalog_table "
                + "(id BIGINT PRIMARY KEY, value BIGINT)",
            command));
    assertCatalog(session, true, true, true);
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", command));
    assertCatalog(session, true, true, false);

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
}
