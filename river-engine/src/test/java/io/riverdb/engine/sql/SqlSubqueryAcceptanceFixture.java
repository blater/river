package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;

/** Small real-session fixture shared by contract-level subquery acceptance tests. */
final class SqlSubqueryAcceptanceFixture {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5034433341434345L, 0x5054414e43453031L);
  private final RelationalDatabase database;
  private final SqlSession session;
  private final SqlExecutionResult result = new SqlExecutionResult();

  private SqlSubqueryAcceptanceFixture(
      RelationalDatabase relationalDatabase, SqlSession sqlSession) {
    database = relationalDatabase;
    session = sqlSession;
  }

  static SqlSubqueryAcceptanceFixture create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            root, DATABASE, WalGeneration.of(1), 32, opened));
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessionResult));
    SqlSubqueryAcceptanceFixture fixture =
        new SqlSubqueryAcceptanceFixture(opened.database(), sessionResult.session());
    fixture.seed();
    return fixture;
  }

  SqlSession session() { return session; }
  SqlExecutionResult result() { return result; }

  void assertRows(String sql, long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  void assertSyntheticRows(String sql, long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(0, row.key());
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  void close() {
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private void seed() {
    execute(
        "CREATE TABLE outer_rows "
            + "(id BIGINT PRIMARY KEY,value BIGINT,region BIGINT,"
            + "label VARCHAR(32))");
    execute(
        "CREATE TABLE inner_rows "
            + "(id BIGINT PRIMARY KEY,value BIGINT,region BIGINT)");
    execute(
        "CREATE TABLE scalar_rows "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,value BIGINT)");
    execute(
        "INSERT INTO outer_rows VALUES "
            + "(1,10,1,'first'),(2,20,1,'second'),"
            + "(3,NULL,2,'unknown'),(4,40,3,'東京-🌊-résumé')");
    execute(
        "INSERT INTO inner_rows VALUES "
            + "(10,NULL,1),(11,10,1),(12,30,2),(13,10,2)");
    execute(
        "INSERT INTO scalar_rows VALUES "
            + "(1,1,10),(2,2,20),(3,2,21)");
  }

  void execute(String sql) {
    assertEquals(StatusCode.OK, session.execute(sql, result), sql);
  }
}
