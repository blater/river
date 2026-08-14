package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

final class EmbeddedRiverVariableVarcharTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5641524348415255L, 0x544638524f573031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void storesIndexesOrdersAndReopensBoundedUnicodeText(@TempDir Path root) {
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
            "CREATE TABLE names (id BIGINT PRIMARY KEY, value VARCHAR(32) "
                + "NOT NULL, state VARCHAR(12) DEFAULT '新規🌊')",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO names (id, value) VALUES "
                + "(1, 'résumé-東京-🌊'), (2, 'alpha-longer-than-seven'), "
                + "(3, '東京'), (4, '🌊-wave')",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX names_value ON names(value)", command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value, state FROM names WHERE value='résumé-東京-🌊'", command));
    assertText(command, 0, "résumé-東京-🌊", SqlTypeDescriptor.varchar(32));
    assertText(command, 1, "新規🌊", SqlTypeDescriptor.varchar(12));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO names VALUES (5, '東京', DEFAULT)", command));

    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT value FROM names ORDER BY value", queryResult));
    RiverQuery query = queryResult.query();
    String[] expected = {
        "alpha-longer-than-seven", "résumé-東京-🌊", "東京", "🌊-wave"
    };
    RowResult row = new RowResult();
    for (String value : expected) {
      assertEquals(StatusCode.OK, query.next(row));
      assertText(row, 0, value, SqlTypeDescriptor.varchar(32));
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO names VALUES (6, 'é', DEFAULT), (7, 'é', DEFAULT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM names WHERE value='é'", command));
    assertEquals(6, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM names WHERE value='é'", command));
    assertEquals(7, command.valueAt(0));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE bounded (id BIGINT PRIMARY KEY, value VARCHAR(3))",
            command));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute("INSERT INTO bounded VALUES (1, 'four')", command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO bounded VALUES (2, '🌊🌊🌊')", command));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute("INSERT INTO bounded VALUES (3, '🌊🌊🌊🌊')", command));
    String malformed = "INSERT INTO bounded VALUES (4, '"
        + Character.toString((char) 0xd800) + "')";
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(malformed, command));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        session.execute(
            "CREATE TABLE too_wide (id BIGINT PRIMARY KEY, a VARCHAR(255), "
                + "b VARCHAR(255), c VARCHAR(255), d VARCHAR(255))",
            command));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE sorted_text (id BIGINT PRIMARY KEY, value VARCHAR(32))",
            command));
    int spillRows = 1_025;
    for (int first = 0; first < spillRows; first += 64) {
      int end = Math.min(first + 64, spillRows);
      StringBuilder insert = new StringBuilder("INSERT INTO sorted_text VALUES ");
      for (int key = first; key < end; key++) {
        if (key > first) {
          insert.append(',');
        }
        insert.append('(')
            .append(key)
            .append(",'entry-")
            .append(paddedKey(key))
            .append("-東京🌊')");
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), command));
    }
    queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, value FROM sorted_text ORDER BY value", queryResult));
    query = queryResult.query();
    for (int key = 0; key < spillRows; key++) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(key, row.valueAt(0));
      assertText(row, 1, "entry-" + paddedKey(key) + "-東京🌊",
          SqlTypeDescriptor.varchar(32));
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value, state FROM names WHERE id=1", command));
    assertText(command, 0, "résumé-東京-🌊", SqlTypeDescriptor.varchar(32));
    assertText(command, 1, "新規🌊", SqlTypeDescriptor.varchar(12));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertText(
      CommandResult result,
      int index,
      String expected,
      int descriptor) {
    char[] text = new char[64];
    assertEquals(descriptor, result.typeDescriptorAt(index));
    assertEquals(expected.length(), result.copyTextAt(index, text, 0));
    assertEquals(expected, new String(text, 0, expected.length()));
  }

  private static void assertText(
      RowResult result,
      int index,
      String expected,
      int descriptor) {
    char[] text = new char[64];
    assertEquals(descriptor, result.typeDescriptorAt(index));
    assertEquals(expected.length(), result.copyTextAt(index, text, 0));
    assertEquals(expected, new String(text, 0, expected.length()));
  }

  private static String paddedKey(int key) {
    String value = Integer.toString(key);
    return "0".repeat(4 - value.length()) + value;
  }
}
