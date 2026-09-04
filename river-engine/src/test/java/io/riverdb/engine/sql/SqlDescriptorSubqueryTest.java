package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorSubqueryTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4453435355425152L, 0x5954455354303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void evaluatesDescriptorPredicateSubqueries(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(7), root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE events "
            + "(id BIGINT, category BIGINT, amount BIGINT,PRIMARY KEY(id,category))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO events VALUES (1,10,100),(2,10,200),(3,20,300),"
            + "(4,10,400),(5,30,500)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX events_amount ON events(amount)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE numeric_outer (id BIGINT,value DOUBLE PRECISION,"
            + "PRIMARY KEY(id,value))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE numeric_inner (id BIGINT,value REAL,PRIMARY KEY(id,value))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_outer VALUES (1,-2.0),(2,1.0)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_inner VALUES (10,-2.0)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE predicate_cases (id INTEGER PRIMARY KEY,flag BOOLEAN,"
            + "amount DECIMAL(8,2),sample REAL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO predicate_cases VALUES "
            + "(1,TRUE,10.00,16777216),(2,FALSE,20.00,2.5),(3,NULL,NULL,NULL)",
        result));

    assertRows(session,
        "SELECT id FROM events WHERE amount="
            + "(SELECT amount FROM events WHERE id=2)",
        new long[] {2});
    assertRows(session,
        "SELECT id FROM events WHERE amount="
            + "(SELECT amount FROM events WHERE id=999)", new long[0]);
    assertRows(session,
        "SELECT id FROM events WHERE category IN "
            + "(SELECT category FROM events WHERE id=3)", new long[] {3});
    assertRows(session,
        "SELECT id FROM events WHERE category NOT IN "
            + "(SELECT category FROM events WHERE id=999)",
        new long[] {1, 2, 3, 4, 5});
    assertRows(session,
        "SELECT id FROM events WHERE category NOT IN "
            + "(SELECT NULL FROM events WHERE id=1)", new long[0]);
    assertRows(session,
        "SELECT e.id FROM events e WHERE e.category IN "
            + "(SELECT i.category FROM events i WHERE i.id=e.id)",
        new long[] {1, 2, 3, 4, 5});
    assertCardinality(session,
        "SELECT id FROM events WHERE amount="
            + "(SELECT category FROM events WHERE category=10)");
    assertCardinality(session,
        "SELECT e.id FROM events e WHERE e.amount="
            + "(SELECT i.amount FROM events i WHERE i.category=e.category)");
    assertRows(session,
        "SELECT id FROM events WHERE EXISTS "
            + "(SELECT id FROM events WHERE category=999)", new long[0]);
    assertRows(session,
        "SELECT id FROM numeric_outer WHERE value IN "
            + "(SELECT value FROM numeric_inner)", new long[] {1});
    assertRows(session,
        "SELECT o.id FROM numeric_outer o WHERE EXISTS "
            + "(SELECT i.id FROM numeric_inner i WHERE i.value<o.value)",
        new long[] {2});
    assertRows(session,
        "SELECT id FROM predicate_cases WHERE flag IS NULL", new long[] {3});
    assertRows(session,
        "SELECT id FROM predicate_cases WHERE flag NOT IN (TRUE,NULL)", new long[0]);
    assertRows(session,
        "SELECT id FROM predicate_cases WHERE amount BETWEEN 10 AND 20.000",
        new long[] {1, 2});
    assertRows(session,
        "SELECT id FROM predicate_cases WHERE amount NOT BETWEEN 10.01 AND 19.99",
        new long[] {1, 2});
    assertRows(session,
        "SELECT id FROM predicate_cases WHERE 10.00<amount", new long[] {2});
    assertRows(session,
        "SELECT id FROM predicate_cases WHERE sample=16777217", new long[0]);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertRows(SqlSession session, String sql, long[] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertCardinality(SqlSession session, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.CARDINALITY_VIOLATION, session.beginScan(sql, cursor));
    assertFalse(row.isAvailable());
    assertFalse(cursor.isActive());
  }
}
