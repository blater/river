package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
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

final class EmbeddedRiverVarcharTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5641524348415238L, 0x454e47494e453031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void executesIndexedVarcharValuesAndReopens(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE products "
                + "(id BIGINT PRIMARY KEY, code VARCHAR(7) NOT NULL, "
                + "tag VARCHAR(7) DEFAULT 'none', "
                + "state VARCHAR(7) NOT NULL DEFAULT 'new', qty BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO products (id, code, qty) VALUES "
                + "(1, 'beta', 10), (2, 'alpha', 20)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO products VALUES (3, 'gamma', NULL, 'old', 30)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO products VALUES (4, 'it''s', 'group', DEFAULT, NULL)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX products_code ON products(code)", command));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX products_tag ON products(tag)", command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT code, tag, state FROM products WHERE code='alpha'", command));
    assertEquals(true, command.rowAvailable());
    assertText(command, 0, "alpha");
    assertText(command, 1, "none");
    assertText(command, 2, "new");
    assertEquals(SqlTypeDescriptor.varchar(7), command.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.varchar(7), command.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.varchar(7), command.typeDescriptorAt(2));

    assertSingleKey(session, "SELECT id FROM products WHERE tag='group'", 4);
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO products VALUES (5, 'alpha', NULL, DEFAULT, 50)", command));

    assertLexicalOrder(session);
    assertTextRange(session);

    assertEquals(StatusCode.OK, session.execute("BEGIN", command));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE products SET code='delta', tag=DEFAULT WHERE id=3", command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM products WHERE code='delta'", command));
    assertEquals(3, command.key());
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", command));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id FROM products WHERE code='delta'", command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM products WHERE code='gamma'", command));
    assertEquals(3, command.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT tag FROM products WHERE id=3", command));
    assertEquals(true, command.isVarchar(0));
    assertEquals(true, command.isNull(0));
    assertEquals(-1, command.textLengthAt(0));

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MIN(code) FROM products", command));
    assertText(command, 0, "alpha");
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute("SELECT SUM(code) FROM products", command));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT code, tag, state FROM products WHERE id=4", command));
    assertText(command, 0, "it's");
    assertText(command, 1, "group");
    assertText(command, 2, "new");
    assertSingleKey(session, "SELECT id FROM products WHERE code='gamma'", 3);
    assertSingleKey(session, "SELECT id FROM products WHERE tag='group'", 4);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertLexicalOrder(RiverSession session) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT code FROM products ORDER BY code", opened));
    RiverQuery query = opened.query();
    assertEquals(true, query.columnIsVarchar(0));
    assertEquals(SqlTypeDescriptor.varchar(7), query.columnTypeDescriptor(0));
    RowResult row = new RowResult();
    String[] expected = {"alpha", "beta", "gamma", "it's"};
    for (String value : expected) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(true, row.isAvailable());
      assertText(row, 0, value);
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertTextRange(RiverSession session) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT code FROM products WHERE code >= 'alpha' AND code < 'gamma' "
                + "ORDER BY code",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertText(row, 0, "alpha");
    assertEquals(StatusCode.OK, query.next(row));
    assertText(row, 0, "beta");
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertSingleKey(
      RiverSession session,
      String sql,
      long expectedKey) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(expectedKey, row.key());
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertText(CommandResult result, int index, String expected) {
    char[] characters = new char[8];
    assertEquals(true, result.isVarchar(index));
    assertEquals(SqlTypeDescriptor.varchar(7), result.typeDescriptorAt(index));
    assertEquals(expected.length(), result.textLengthAt(index));
    assertEquals(expected.length(), result.copyTextAt(index, characters, 0));
    assertEquals(expected, new String(characters, 0, expected.length()));
  }

  private static void assertText(RowResult result, int index, String expected) {
    char[] characters = new char[8];
    assertEquals(true, result.isVarchar(index));
    assertEquals(SqlTypeDescriptor.varchar(7), result.typeDescriptorAt(index));
    assertEquals(expected.length(), result.textLengthAt(index));
    assertEquals(expected.length(), result.copyTextAt(index, characters, 0));
    assertEquals(expected, new String(characters, 0, expected.length()));
  }
}
