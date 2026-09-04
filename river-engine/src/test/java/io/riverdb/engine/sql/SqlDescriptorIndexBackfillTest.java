package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorIndexBackfillTest {
  private static final int ROWS = 1_024;
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x494e444558424154L, 0x43484255494c4431L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void streamsOrdinaryAndUniqueCompositeNumericIndexesAcrossReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createRows(session, result, false);

    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX measurements_lookup ON measurements(tenant,amount)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX measurements_unique "
            + "ON measurements(amount,ratio)", result));
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO measurements VALUES (2001,1,1.000001,1.5)", result));
    assertCount(session, result, ROWS, "tenant>=0");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertCount(session, result, 1, "tenant=1 AND amount=1.000001");
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO measurements VALUES (2002,2,1.000001,1.5)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rolledBackBatchedBuildCleansUpAndCanRetryAfterReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession writer = session(database);
    SqlSession reader = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createRows(writer, result, false);

    assertEquals(StatusCode.OK, writer.execute("BEGIN", result));
    assertEquals(StatusCode.OK, writer.execute(
        "CREATE UNIQUE INDEX retry_unique ON measurements(amount,ratio)", result));
    assertEquals(StatusCode.RETRY,
        reader.execute("SELECT COUNT(*) FROM measurements", result));
    assertEquals(StatusCode.OK, writer.execute("ROLLBACK", result));
    assertCount(reader, result, ROWS, "tenant>=0");
    assertEquals(StatusCode.OK, writer.close());
    assertEquals(StatusCode.OK, reader.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    writer = session(database);
    assertEquals(StatusCode.OK, writer.execute(
        "CREATE UNIQUE INDEX retry_unique ON measurements(amount,ratio)", result));
    assertEquals(StatusCode.UNIQUE_VIOLATION, writer.execute(
        "INSERT INTO measurements VALUES (3001,3,1.000001,1.5)", result));
    assertEquals(StatusCode.OK, writer.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void duplicateAcrossBuildBatchesFailsWithoutPublishingAndRetrySucceeds(
      @TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createRows(session, result, true);

    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "CREATE UNIQUE INDEX duplicate_unique ON measurements(amount,ratio)", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE measurements SET amount=1024.001024,ratio=1024.5 WHERE id=1024", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX duplicate_unique ON measurements(amount,ratio)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createRows(
      SqlSession session, SqlExecutionResult result, boolean duplicateLast) {
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE measurements (id INTEGER PRIMARY KEY,tenant INTEGER,"
            + "amount DECIMAL(22,6),ratio DOUBLE PRECISION)", result));
    for (int first = 1; first <= ROWS; first += 64) {
      int last = Math.min(ROWS, first + 63);
      StringBuilder sql = new StringBuilder("INSERT INTO measurements VALUES ");
      for (int id = first; id <= last; id++) {
        if (id != first) sql.append(',');
        int value = duplicateLast && id == ROWS ? 1 : id;
        sql.append('(').append(id).append(',').append(id % 17).append(',')
            .append(value).append('.');
        appendSixDigits(sql, value);
        sql.append(',').append(value).append(".5)");
      }
      assertEquals(StatusCode.OK, session.execute(sql.toString(), result), sql.toString());
    }
  }

  private static void appendSixDigits(StringBuilder target, int value) {
    int divisor = 100_000;
    while (divisor > value) {
      target.append('0');
      divisor /= 10;
    }
    target.append(value);
  }

  private static void assertCount(
      SqlSession session, SqlExecutionResult result, long expected, String predicate) {
    assertEquals(StatusCode.OK, session.execute(
        "SELECT COUNT(*) FROM measurements WHERE " + predicate, result));
    assertEquals(expected, result.value());
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static RelationalDatabase open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
