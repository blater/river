package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.TableDefinition;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path evidence for the full bounded JOIN-role capacity. */
final class Sql64RoleJoinExecutionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x3634524f4c454a31L, 0x4a4f494e534c4943L);
  private static volatile long allocationGuard;

  @Test
  void executesSixtyFourRolesAndRejectsSixtyFiveBeforeCursorOpen(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            root, DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    TableDefinition fixture = new TableDefinition();
    assertEquals(
        StatusCode.OK,
        database.createTable("role_row", "id", "marker", fixture));
    assertEquals(
        StatusCode.OK,
        database.createTable("role_driver", "id", "padding", fixture));
    SqlSession session = null;
    try {
      SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
      assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
      session = sessionResult.session();
      SqlExecutionResult result = new SqlExecutionResult();
      createFixture(session, result);

      String inner = joinQuery(64, false, true);
      assertInnerResult(session, result, inner);
      assertLeftNullExtension(session, result);
      assertExplainEvidence(session, result, inner);
      assertNonIndexedRolesReachFullDepth(session, result);
      assertNestedHighRoleScope(session, result);
      assertWarmedRowPublicationDoesNotAllocate(
          session, result, joinQuery(64, false, false));

      SqlScanCursor rejected = new SqlScanCursor();
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          session.beginScan(joinQuery(65, false, false), rejected));
      assertFalse(rejected.isActive());
    } finally {
      if (session != null) session.close();
      database.close();
    }
  }

  private static void createFixture(
      SqlSession session, SqlExecutionResult result) {
    StringBuilder roleRows = new StringBuilder("INSERT INTO role_row VALUES ");
    for (int id = 1; id <= 64; id++) {
      if (id > 1) roleRows.append(',');
      roleRows.append('(').append(id).append(',').append(id).append(')');
    }
    assertEquals(StatusCode.OK, session.execute(roleRows.toString(), result));
    StringBuilder rows = new StringBuilder("INSERT INTO role_driver VALUES ");
    for (int id = 1; id <= 40; id++) {
      if (id > 1) rows.append(',');
      rows.append('(').append(id).append(",0)");
    }
    assertEquals(StatusCode.OK, session.execute(rows.toString(), result));
  }

  private static void assertInnerResult(
      SqlSession session, SqlExecutionResult result, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(80, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertLeftNullExtension(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(joinQuery(64, true, false), cursor));
    assertTrue(session.scanColumnIsNullable(cursor, 0));
    for (int id = 1; id <= 40; id++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertTrue(row.isNull(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertNonIndexedRolesReachFullDepth(
      SqlSession session, SqlExecutionResult result) {
    String sql = joinQuery(64, false, false)
        .replace(".id=r0.id", ".marker=r0.id");
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    int rows = 0;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) rows++;
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(40, rows);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    ThreadMXBean bean = allocationBean();
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    status = session.nextScan(cursor, row);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    allocationGuard += row.valueAt(0);
    assertEquals(StatusCode.OK, status);
    assertEquals(4, row.valueAt(0));
    assertEquals(0, allocated,
        "warmed 64-scan JOIN advance allocated bytes: " + allocated);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertNestedHighRoleScope(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(nestedHighRoleQuery(), cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(41, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertExplainEvidence(
      SqlSession session, SqlExecutionResult result, String inner) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN " + inner, cursor));
    int steps = 0;
    int joinPredicates = 0;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      steps++;
      if (textEquals(row, "on")) joinPredicates++;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(191, steps);
    assertEquals(63, joinPredicates);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan("EXPLAIN " + joinQuery(64, true, false), cursor));
    boolean terminalExtension = false;
    boolean terminalLeft = false;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      if (textEquals(row, "extend") && row.valueAt(1) == 63) {
        terminalExtension = true;
      }
      if (isLeftOperator(row)) terminalLeft = true;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertTrue(terminalExtension);
    assertTrue(terminalLeft);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static boolean isLeftOperator(SqlScanRowResult row) {
    return textEquals(row, "left")
        || textEquals(row, "hleft")
        || textEquals(row, "mleft")
        || textEquals(row, "fbleft");
  }

  private static boolean textEquals(SqlScanRowResult row, String expected) {
    if (row.textLengthAt(0) != expected.length()) return false;
    char[] actual = new char[expected.length()];
    if (row.copyTextAt(0, actual, 0) != actual.length) return false;
    for (int index = 0; index < actual.length; index++) {
      if (actual[index] != expected.charAt(index)) return false;
    }
    return true;
  }

  private static void assertWarmedRowPublicationDoesNotAllocate(
      SqlSession session, SqlExecutionResult result, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (int iteration = 0; iteration < 32; iteration++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    }

    ThreadMXBean bean = allocationBean();
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    StatusCode status = session.nextScan(cursor, row);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    allocationGuard += row.valueAt(0);
    assertEquals(StatusCode.OK, status);
    assertEquals(0, allocated,
        "warmed 64-role JOIN row publication allocated bytes: " + allocated);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    if (!bean.isThreadAllocatedMemoryEnabled()) {
      bean.setThreadAllocatedMemoryEnabled(true);
    }
    return bean;
  }

  private static String joinQuery(
      int roles, boolean terminalLeft, boolean highRolePredicate) {
    StringBuilder sql = new StringBuilder(roles * 48);
    sql.append("SELECT ");
    if (terminalLeft) {
      sql.append('r').append(roles - 1).append(".marker");
    } else {
      sql.append("r0.id+r").append(roles - 1).append(".marker");
    }
    sql.append(" FROM role_driver r0");
    for (int role = 1; role < roles; role++) {
      sql.append(terminalLeft && role == roles - 1
          ? " LEFT JOIN role_row" : " JOIN role_row")
          .append(" r").append(role).append(" ON r").append(role).append(".id=r0.id");
      if (terminalLeft && role == roles - 1) sql.append("+100");
    }
    if (highRolePredicate) {
      sql.append(" WHERE r").append(roles - 1).append(".marker=")
          .append(40);
    }
    return sql.toString();
  }

  private static String nestedHighRoleQuery() {
    StringBuilder sql = new StringBuilder(3_500);
    sql.append("SELECT o.id FROM role_row o WHERE o.id=41 AND EXISTS (")
        .append("SELECT r0.id FROM role_driver r0");
    for (int role = 1; role < 64; role++) {
      sql.append(" JOIN role_row r").append(role).append(" ON r")
          .append(role).append(".id=r0.id");
      if (role == 63) sql.append("+1");
    }
    return sql.append(" WHERE r0.id=40 AND r63.marker=o.id)").toString();
  }

}
