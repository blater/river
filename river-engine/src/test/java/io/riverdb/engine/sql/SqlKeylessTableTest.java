package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlKeylessTableTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4b45594c45535354L, 0x41424c4530303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void preservesDuplicateRowsAndHiddenIdentityAcrossMutationsAndReopen(
      @TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE history (customer BIGINT,note VARCHAR(16))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX history_customer ON history(customer,note)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO history VALUES (7,'same'),(7,'same'),(8,'other')", result));
    assertCount(session, 2, "customer=7");
    assertCount(session, 2, "note='same'");
    assertEquals(StatusCode.OK,
        session.execute("UPDATE history SET note='changed' WHERE customer=7", result));
    assertEquals(2, result.affectedRows());
    assertCount(session, 2, "customer=7");
    assertCount(session, 0, "note='same'");
    assertCount(session, 2, "note='changed'");
    assertCount(session, 2, "customer=7 AND note='changed'");
    assertEquals(StatusCode.OK,
        session.execute("DELETE FROM history WHERE customer=7", result));
    assertEquals(2, result.affectedRows());
    assertCount(session, 0, "customer=7");
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO history VALUES (9,'repeat'),(9,'repeat')", result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertCount(session, 2, "customer=9 AND note='repeat'");
    assertCount(session, 1, "customer=8 AND note='other'");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void pointSelectPublishesOnlyAfterKeylessCardinalityIsKnown(
      @TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession first = session(database);
    SqlSession second = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, first.execute(
        "CREATE TABLE point_history (customer BIGINT,note VARCHAR(16),payload BIGINT)",
        result));
    assertEquals(StatusCode.OK, first.execute(
        "CREATE INDEX point_history_customer ON point_history(customer)", result));
    assertEquals(StatusCode.OK, first.execute(
        "INSERT INTO point_history VALUES (7,'first',70),(7,'second',71),(8,'only',80)",
        result));

    assertEquals(StatusCode.CONFLICT, first.execute(
        "SELECT note,payload FROM point_history WHERE customer=9", result));
    assertReset(result);
    assertEquals(StatusCode.OK, first.execute(
        "SELECT note,payload FROM point_history WHERE customer=8", result));
    assertEquals(2, result.columnCount());
    assertEquals(80, result.valueAt(1));
    char[] text = new char[16];
    int copied = result.copyTextAt(0, text, 0);
    assertEquals("only", new String(text, 0, copied));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, first.execute(
        "SELECT note,payload FROM point_history WHERE customer=7", result));
    assertReset(result);

    assertEquals(StatusCode.OK, first.execute("BEGIN", result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, first.execute(
        "SELECT note FROM point_history WHERE customer=7 FOR UPDATE", result));
    assertEquals(StatusCode.OK, second.execute(
        "UPDATE point_history SET payload=72 WHERE customer=7", result));
    assertEquals(2, result.affectedRows());
    assertEquals(StatusCode.OK, first.execute("ROLLBACK", result));

    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertReset(SqlExecutionResult result) {
    assertEquals(false, result.hasValue());
    assertEquals(0, result.columnCount());
    assertEquals(0, result.affectedRows());
    assertEquals(0, result.key());
    assertEquals(0, result.value());
    assertEquals(0, result.commitSequence());
  }

  private static void assertCount(SqlSession session, long expected, String predicate) {
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM history WHERE " + predicate, result));
    assertEquals(expected, result.value());
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static RelationalDatabase open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
