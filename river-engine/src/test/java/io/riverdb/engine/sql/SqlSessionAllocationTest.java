package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSessionAllocationTest {
  private static volatile long allocationGuard;

  @Test
  void warmedPointSelectReusesSqlAndKernelState(@TempDir Path root) {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            root,
            DatabaseIncarnation.of(769, 773),
            WalGeneration.of(1),
            4,
            opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE t (id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)",
            result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO t VALUES (1, 10, 7)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX t_region ON t(region)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE labels (id BIGINT PRIMARY KEY, region BIGINT, code BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO labels VALUES (1, 7, 70), (2, 7, 71)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX labels_region ON labels(region)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE raw_labels "
                + "(id BIGINT PRIMARY KEY, region BIGINT, code BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO raw_labels VALUES (1, 7, 70), (2, 7, 71)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE nested_labels "
                + "(id BIGINT PRIMARY KEY, region BIGINT, padding VARCHAR(128))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO nested_labels VALUES "
                + "(1, 7, 'abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
                + "abcdefghijklmnopqrstuvwxyz')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE texts (id BIGINT PRIMARY KEY, label VARCHAR(7) NOT NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO texts VALUES (1, 'alpha')", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX texts_label ON texts(label)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW regional AS "
                + "SELECT id, balance, region FROM t WHERE balance=10",
            result));
    for (int index = 0; index < 100; index++) {
      exercise(session, result);
      exerciseCount(session, result);
      exerciseText(session, result);
    }
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exercise(session, result);
      exerciseCount(session, result);
      exerciseText(session, result);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed SQL point select allocated bytes: " + allocated);

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult scanRow = new SqlScanRowResult();
    for (int index = 0; index < 100; index++) {
      exerciseScan(session, cursor, scanRow, result);
      exerciseSort(session, cursor, scanRow, result);
      exerciseScalar(session, cursor, scanRow, result);
      exerciseExists(session, cursor, scanRow, result);
      exerciseCorrelatedMembership(session, cursor, scanRow, result);
      exerciseRecursiveExists(session, cursor, scanRow, result);
      exerciseAggregate(session, cursor, scanRow, result);
      exerciseJoin(session, cursor, scanRow, result);
      exerciseUnindexedJoin(session, cursor, scanRow, result);
      exerciseLeftJoin(session, cursor, scanRow, result);
      exerciseDisjunction(session, cursor, scanRow, result);
      exerciseView(session, cursor, scanRow, result);
      exerciseExplain(session, cursor, scanRow, result);
    }
    before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exerciseScan(session, cursor, scanRow, result);
      exerciseSort(session, cursor, scanRow, result);
      exerciseScalar(session, cursor, scanRow, result);
      exerciseExists(session, cursor, scanRow, result);
      exerciseCorrelatedMembership(session, cursor, scanRow, result);
      exerciseRecursiveExists(session, cursor, scanRow, result);
      exerciseAggregate(session, cursor, scanRow, result);
      exerciseJoin(session, cursor, scanRow, result);
      exerciseUnindexedJoin(session, cursor, scanRow, result);
      exerciseLeftJoin(session, cursor, scanRow, result);
      exerciseDisjunction(session, cursor, scanRow, result);
      exerciseView(session, cursor, scanRow, result);
      exerciseExplain(session, cursor, scanRow, result);
    }
    allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed SQL scan allocated bytes: " + allocated);
    assertEquals(StatusCode.OK, database.close());
  }

  private static void exercise(SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT region FROM t WHERE id=1 AND region=7", result).ordinal();
    allocationGuard += result.value();
  }

  private static void exerciseCount(SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT COUNT(*) FROM t WHERE region=7 AND balance=10", result).ordinal();
    allocationGuard += result.value();
  }

  private static void exerciseText(SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT label FROM texts WHERE label='alpha'", result).ordinal();
    allocationGuard += result.value();
  }

  private static void exerciseScan(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT id, balance FROM t WHERE region=7 AND balance=10", cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.key();
    allocationGuard += row.value();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseSort(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT d.id, d.balance FROM "
            + "(SELECT id, balance, region FROM t WHERE t.region=7) d "
            + "ORDER BY balance",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseAggregate(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT region, SUM(balance) FROM t WHERE balance=10 AND region=7 "
            + "GROUP BY region HAVING SUM(balance) >= 10 ORDER BY region",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseScalar(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT id, balance FROM t WHERE balance="
            + "(SELECT balance FROM t WHERE t.id=1)",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseExists(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT id FROM t WHERE EXISTS (SELECT id FROM labels WHERE labels.region=7)",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseCorrelatedMembership(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT t.id FROM t WHERE t.region IN "
            + "(SELECT labels.region FROM labels WHERE labels.region=t.region)",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseRecursiveExists(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT t.id FROM t WHERE EXISTS "
            + "(SELECT nested_labels.id FROM nested_labels "
            + "WHERE nested_labels.region=t.region AND EXISTS "
            + "(SELECT raw_labels.id FROM raw_labels "
            + "WHERE raw_labels.region=nested_labels.region))",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseJoin(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT t.id, labels.code FROM t "
            + "JOIN labels ON t.region=labels.region "
            + "WHERE t.id=1 AND labels.code >= 70 AND labels.code < 72",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseExplain(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "EXPLAIN SELECT id, balance FROM t WHERE region=7", cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseUnindexedJoin(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT t.id, raw_labels.code FROM t "
            + "JOIN raw_labels ON t.region=raw_labels.region WHERE t.id=1",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseLeftJoin(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT t.id, raw_labels.code FROM t "
            + "LEFT JOIN raw_labels ON t.balance=raw_labels.region "
            + "WHERE t.id=1 AND raw_labels.code IS NULL",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.nullMask();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseDisjunction(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT id FROM t WHERE region=7 OR balance=999", cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseView(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT id FROM regional WHERE region=7", cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }
}
