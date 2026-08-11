package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlLeftJoinTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4c4546544a4f494eL, 0x53454d414e544943L);

  @Test
  void preservesUnmatchedRowsAcrossIndexedAndUnindexedInnerAccess(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            root, DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE parents "
                + "(id BIGINT PRIMARY KEY, category BIGINT)", execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO parents VALUES (1,10),(2,20),(3,30),(4,NULL)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE labels "
                + "(id BIGINT PRIMARY KEY, category BIGINT, code BIGINT)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO labels VALUES "
                + "(1,10,100),(2,10,101),(3,20,200),(4,NULL,999)",
            execution));

    assertLeftRows(session, execution);
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX labels_category ON labels(category)", execution));
    assertLeftRows(session, execution);

    SqlScanCursor cursor = new SqlScanCursor();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginScan(
            "SELECT parents.id, labels.code FROM parents LEFT JOIN labels "
                + "ON parents.category=labels.category WHERE labels.code=100",
            cursor));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT parents.id, labels.code FROM parents LEFT JOIN labels "
                + "ON parents.category=labels.category",
            cursor));
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertTrue(row.isVarchar(0));
    assertEquals(PackedText.pack("left"), row.valueAt(0));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertLeftRows(
      SqlSession session,
      SqlExecutionResult execution) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT parents.id, labels.code FROM parents "
                + "LEFT OUTER JOIN labels ON parents.category=labels.category",
            cursor));
    long codeSum = 0;
    long codeProduct = 1;
    for (int index = 0; index < 2; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(1, row.valueAt(0));
      codeSum += row.valueAt(1);
      codeProduct *= row.valueAt(1);
    }
    assertEquals(201, codeSum);
    assertEquals(10100, codeProduct);
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.valueAt(0));
    assertEquals(200, row.valueAt(1));
    for (long parent : new long[] {3, 4}) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(parent, row.valueAt(0));
      assertTrue(row.isNull(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }
}
