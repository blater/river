package io.riverdb.engine.sql;

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

/** Public-session execution evidence for joined predicate-subquery blocks. */
final class SqlJoinedSubqueryTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a4f494e45445351L, 0x5545525954455354L);

  @Test
  void executesTwoThreeAndEightRolesWithRecursiveContinuation(@TempDir Path root) {
    Fixture fixture = open(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT b.id FROM role_rows a JOIN role_rows b ON a.id=b.id "
            + "WHERE b.id=o.id AND b.value=o.value)",
        1, 2, 3);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT c.id FROM role_rows a JOIN role_rows b ON a.id=b.id "
            + "JOIN role_rows c ON b.id=c.id "
            + "WHERE c.id=o.id AND c.value=o.value)",
        1, 2, 3);
    fixture.assertRows(
        "SELECT a.id FROM role_rows a JOIN role_rows b ON a.id=b.id "
            + "JOIN role_rows c ON b.id=c.id JOIN role_rows d ON c.id=d.id "
            + "JOIN role_rows e ON d.id=e.id JOIN role_rows f ON e.id=f.id "
            + "JOIN role_rows g ON f.id=g.id JOIN sentinel_rows h ON g.id=h.id "
            + "WHERE EXISTS (SELECT p.id FROM probe_rows p "
            + "WHERE p.id=a.id AND p.value=h.sentinel)",
        1, 3);
    fixture.assertRows(
        "SELECT a.id FROM role_rows a JOIN role_rows b ON a.id=b.id "
            + "WHERE EXISTS (SELECT c.id FROM role_rows c "
            + "JOIN probe_rows d ON c.id=d.id "
            + "WHERE c.id=a.id AND d.value=b.value AND d.id=2)",
        2);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT r.id FROM role_rows r WHERE r.id=o.id)",
        1, 2, 3);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT b.id FROM role_rows a JOIN role_rows b ON a.id=b.id "
            + "WHERE b.id=o.id)",
        1, 2, 3);
    fixture.close();
  }

  @Test
  void preservesLeftNullsAndOwnedUnicodeValues(@TempDir Path root) {
    Fixture fixture = open(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value="
            + "(SELECT b.value FROM role_rows a LEFT JOIN sparse_rows b "
            + "ON a.id=b.id WHERE a.id=o.id)",
        1, 2);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT a.id FROM role_rows a LEFT JOIN sparse_rows b "
            + "ON a.id=b.id JOIN probe_rows p ON b.id=p.id "
            + "WHERE a.id=o.id)",
        1, 2);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT a.id FROM role_rows a LEFT JOIN sparse_rows b "
            + "ON a.id=b.id LEFT JOIN probe_rows p ON b.id=p.id "
            + "WHERE a.id=o.id AND p.id IS NULL)",
        3);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.label IN "
            + "(SELECT b.label FROM role_rows a JOIN role_rows b ON a.id=b.id "
            + "WHERE b.id=o.id)",
        1, 2, 3);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.label="
            + "(SELECT b.label FROM role_rows a JOIN probe_rows b ON a.id=b.id "
            + "WHERE b.id=o.id)",
        1, 2, 3);
    fixture.assertRows(
        "SELECT a.id FROM role_rows a JOIN sentinel_rows h ON a.id=h.id "
            + "WHERE a.label IN (SELECT p.label FROM probe_rows p "
            + "WHERE p.id=a.id AND p.label=h.label)",
        1, 3);

    fixture.close();
  }

  @Test
  void latchesJoinedChildFailureThenClosesAndReuses(@TempDir Path root) {
    Fixture fixture = open(root);
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    String temporal = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT g.id FROM gap_times g JOIN zoned_times z ON "
        + "g.observed AT TIME ZONE 'Europe/London'=z.observed "
        + "WHERE g.id=o.id)";

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        fixture.session.beginScan(temporal, cursor));
    assertFalse(row.isAvailable());
    assertFalse(cursor.isActive());
    assertEquals(StatusCode.OK, cursor.reset());
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3);

    String sql = "SELECT o.id FROM outer_rows o WHERE o.value="
        + "(SELECT d.value FROM role_rows r JOIN duplicate_rows d "
        + "ON r.id=d.owner WHERE r.id=o.id)";

    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session.beginScan(sql, cursor));
    assertFalse(row.isAvailable());
    assertFalse(cursor.isActive());
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3);
    StatusCode pointStatus = fixture.session.execute(
        "SELECT lid FROM (SELECT a.id AS lid FROM role_rows a "
            + "JOIN probe_rows p ON a.id=p.id WHERE a.value="
            + "(SELECT d.value FROM role_rows r JOIN duplicate_rows d "
            + "ON r.id=d.owner WHERE r.id=a.id)) joined",
        fixture.result);
    assertEquals(StatusCode.CARDINALITY_VIOLATION, pointStatus);
    assertFalse(fixture.result.hasValue());
    assertEquals(0, fixture.result.affectedRows());
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT d.id FROM role_rows r JOIN duplicate_rows d "
            + "ON r.id=d.owner WHERE r.id=o.id)",
        1, 2);

    fixture.close();
  }

  private static Fixture open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, WalGeneration.of(1), 32, opened));
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessionResult));
    Fixture fixture = new Fixture(opened.database(), sessionResult.session());
    fixture.execute(
        "CREATE TABLE outer_rows "
            + "(id BIGINT PRIMARY KEY,value BIGINT,label VARCHAR(128))");
    fixture.execute(
        "CREATE TABLE role_rows "
            + "(id BIGINT PRIMARY KEY,value BIGINT,label VARCHAR(128))");
    fixture.execute(
        "CREATE TABLE probe_rows "
            + "(id BIGINT PRIMARY KEY,value BIGINT,label VARCHAR(128))");
    fixture.execute(
        "CREATE TABLE sparse_rows "
            + "(id BIGINT PRIMARY KEY,value BIGINT,label VARCHAR(128))");
    fixture.execute(
        "CREATE TABLE duplicate_rows "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,value BIGINT)");
    fixture.execute(
        "CREATE TABLE sentinel_rows "
            + "(id BIGINT PRIMARY KEY,sentinel BIGINT,label VARCHAR(128))");
    fixture.execute(
        "CREATE TABLE gap_times "
            + "(id BIGINT PRIMARY KEY,observed TIMESTAMP(6))");
    fixture.execute(
        "CREATE TABLE zoned_times "
            + "(id BIGINT PRIMARY KEY,observed TIMESTAMP WITH TIME ZONE)");
    String values = "(1,10,'猫😀-a-very-long-owned-value'),"
        + "(2,20,'éclair-β-第二-long-owned-value'),"
        + "(3,30,'مرحبا-🌊-third-long-owned-value')";
    fixture.execute("INSERT INTO outer_rows VALUES " + values);
    fixture.execute("INSERT INTO role_rows VALUES " + values);
    fixture.execute("INSERT INTO probe_rows VALUES " + values);
    fixture.execute(
        "INSERT INTO sparse_rows VALUES "
            + "(1,10,'猫😀-a-very-long-owned-value'),"
            + "(2,20,'éclair-β-第二-long-owned-value')");
    fixture.execute(
        "INSERT INTO duplicate_rows VALUES "
            + "(11,1,10),(21,2,20),(22,2,20)");
    fixture.execute(
        "INSERT INTO sentinel_rows VALUES "
            + "(1,10,'猫😀-a-very-long-owned-value'),"
            + "(2,999,'different-β-第二-long-owned-value'),"
            + "(3,30,'مرحبا-🌊-third-long-owned-value')");
    fixture.execute(
        "INSERT INTO gap_times VALUES "
            + "(1,TIMESTAMP '2024-03-31 00:30:00'),"
            + "(2,TIMESTAMP '2024-03-31 01:30:00')");
    fixture.execute(
        "INSERT INTO zoned_times VALUES "
            + "(1,TIMESTAMP WITH TIME ZONE '2024-03-31 00:30:00+00:00'),"
            + "(2,TIMESTAMP WITH TIME ZONE '2024-03-31 02:30:00+01:00')");
    return fixture;
  }

  private static final class Fixture {
    private final RelationalDatabase database;
    private final SqlSession session;
    private final SqlExecutionResult result = new SqlExecutionResult();

    private Fixture(RelationalDatabase relationalDatabase, SqlSession sqlSession) {
      database = relationalDatabase;
      session = sqlSession;
    }

    private void execute(String sql) {
      assertEquals(StatusCode.OK, session.execute(sql, result), sql);
    }

    private void assertRows(String sql, long... expected) {
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
      for (long value : expected) {
        assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
        assertEquals(value, row.valueAt(0), sql);
      }
      assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
      assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
    }

    private void close() {
      assertEquals(StatusCode.OK, session.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }
}
