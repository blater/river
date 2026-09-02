package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path descriptor/legacy join fallback semantics and shape evidence. */
final class SqlDescriptorMixedJoinTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x444553434a4f494eL, 0x4d49584544303031L);
  private static volatile long allocationGuard;

  @Test
  void joinsCompositeDescriptorKeysWithTypedPredicatesAndLeftExtension(
      @TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createDescriptorTables(session, result);

    assertRows(
        session,
        result,
        "SELECT a.marker,b.label FROM descriptor_left a JOIN descriptor_right b "
            + "ON a.tenant=b.tenant AND a.code=b.code AND a.day=b.day "
            + "AND a.score=b.score AND a.ratio=b.ratio "
            + "WHERE b.amount=12.50",
        new long[] {101},
        new String[] {"matched"});

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a.marker,b.label FROM descriptor_left a LEFT JOIN descriptor_right b "
            + "ON a.tenant=b.tenant AND a.code=b.code AND a.day=b.day", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(101, row.valueAt(0));
    assertText(row, 1, "matched");
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(202, row.valueAt(0));
    assertTrue(row.isNull(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void joinsDescriptorAndLegacyInEitherRoleOrder(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createDescriptorTables(session, result);
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE legacy_rows (id BIGINT PRIMARY KEY,tenant INTEGER,"
            + "code VARCHAR(12),day DATE,marker BIGINT DEFAULT 0)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO legacy_rows VALUES "
            + "(1,7,'alpha',DATE '2025-02-03',901),"
            + "(2,9,'absent',DATE '2025-02-04',902)", result));

    assertRows(
        session,
        result,
        "SELECT d.marker,l.marker FROM descriptor_left d JOIN legacy_rows l "
            + "ON d.tenant=l.tenant AND d.code=l.code AND d.day=l.day",
        new long[] {101, 901},
        null);
    assertRows(
        session,
        result,
        "SELECT l.marker,d.marker FROM legacy_rows l JOIN descriptor_left d "
            + "ON l.tenant=d.tenant AND l.code=d.code AND l.day=d.day "
            + "WHERE d.amount>10.00",
        new long[] {901, 101},
        null);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void compositeDescriptorLookupBoundsCandidateGrowth(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE stock_keys (warehouse INTEGER,item INTEGER,quantity BIGINT,"
            + "PRIMARY KEY(warehouse,item))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE stock_probe (warehouse INTEGER,item INTEGER,marker BIGINT,"
            + "PRIMARY KEY(warehouse,item))", result));
    insertScaleRows(session, result, "stock_keys");
    insertScaleRows(session, result, "stock_probe");

    assertAnalyzeRootRange(session, result);
    assertAnalyzeAccess(
        session,
        result,
        "SELECT a.quantity,b.marker FROM stock_keys a JOIN stock_probe b "
            + "ON a.warehouse=b.warehouse AND a.item=b.item",
        "lookup",
        32);
    assertAnalyzeAccess(
        session,
        result,
        "SELECT a.quantity,b.quantity FROM stock_keys a JOIN stock_keys b "
            + "ON a.warehouse=b.warehouse AND a.item=b.item",
        "lookup",
        32);
    assertDescriptorReopenDoesNotAllocate(session, result);
    assertAnalyzeAccess(
        session,
        result,
        "SELECT a.quantity,b.marker FROM stock_keys a JOIN stock_probe b ON "
            + "(a.warehouse=b.warehouse AND a.item=b.item) OR a.quantity=b.marker",
        "table",
        1_024);
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO stock_keys VALUES (1,33,33)", result));
    assertLeftMissing(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rangeAccessFallsBackWithoutLosingCrossTypeMatches(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE numeric_keys (id INTEGER PRIMARY KEY)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE numeric_bounds (marker INTEGER PRIMARY KEY,"
            + "cut_decimal DECIMAL(4,1),cut_real REAL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_keys VALUES (-2),(-1),(0),(1),(29),(30),(31)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO numeric_bounds VALUES (1,-1.5,29.5)", result));
    assertEquals(3, scalarCount(
        session, result,
        "SELECT COUNT(*) FROM numeric_keys k JOIN numeric_bounds b "
            + "ON k.id>b.cut_decimal AND k.id<2"));
    assertEquals(2, scalarCount(
        session, result,
        "SELECT COUNT(*) FROM numeric_bounds b JOIN numeric_keys k "
            + "ON k.id>b.cut_real"));

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE short_keys (code VARCHAR(3) PRIMARY KEY)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE wide_bounds (marker INTEGER PRIMARY KEY,cut VARCHAR(8))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO short_keys VALUES ('a'),('m'),('zzz')", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO wide_bounds VALUES (1,'zzzz')", result));
    assertEquals(3, scalarCount(
        session, result,
        "SELECT COUNT(*) FROM wide_bounds b JOIN short_keys k ON k.code<b.cut"));
    assertEquals(3, scalarCount(
        session, result,
        "SELECT COUNT(*) FROM wide_bounds b JOIN short_keys k ON k.code<'zzzz'"));

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE composite_numeric_keys (tenant INTEGER,item INTEGER,"
            + "PRIMARY KEY(tenant,item))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE composite_numeric_bounds (marker INTEGER PRIMARY KEY,"
            + "tenant INTEGER,cut DECIMAL(4,1))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO composite_numeric_keys VALUES (1,1),(1,2),(1,3),(2,2)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO composite_numeric_bounds VALUES (1,1,1.5)", result));
    assertEquals(2, scalarCount(
        session, result,
        "SELECT COUNT(*) FROM composite_numeric_bounds b "
            + "JOIN composite_numeric_keys k "
            + "ON k.tenant=b.tenant AND k.item>b.cut"));
    assertEquals(2, scalarCount(
        session, result,
        "SELECT COUNT(*) FROM composite_numeric_keys "
            + "WHERE tenant=1 AND item>1.5"));

    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE composite_text_keys (tenant INTEGER,code VARCHAR(3),"
            + "PRIMARY KEY(tenant,code))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE composite_text_bounds (marker INTEGER PRIMARY KEY,"
            + "tenant INTEGER,cut VARCHAR(8))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO composite_text_keys VALUES (1,'a'),(1,'m'),(1,'zzz'),(2,'m')", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO composite_text_bounds VALUES (1,1,'zzzz')", result));
    assertEquals(3, scalarCount(
        session, result,
        "SELECT COUNT(*) FROM composite_text_bounds b JOIN composite_text_keys k "
            + "ON k.tenant=b.tenant AND k.code<b.cut"));
    assertEquals(3, scalarCount(
        session, result,
        "SELECT COUNT(*) FROM composite_text_keys "
            + "WHERE tenant=1 AND code<'zzzz'"));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void descriptorFallbackRetainsTheSixtyFourRoleCapacity(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE descriptor_root (tenant INTEGER,code VARCHAR(8),marker BIGINT,"
            + "PRIMARY KEY(tenant,code))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE legacy_one (id BIGINT PRIMARY KEY,marker BIGINT DEFAULT 0)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO descriptor_root VALUES (1,'one',7),(2,'two',8)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO legacy_one VALUES (1,9)", result));
    StringBuilder sql = new StringBuilder(4_000);
    sql.append("SELECT d.marker+l63.marker FROM descriptor_root d");
    for (int role = 1; role < 64; role++) {
      sql.append(" JOIN legacy_one l").append(role)
          .append(" ON l").append(role).append(".id=1");
    }
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql.toString(), cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(16, row.valueAt(0));
    ThreadMXBean bean = allocationBean();
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    StatusCode status = session.nextScan(cursor, row);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    allocationGuard += row.valueAt(0);
    assertEquals(StatusCode.OK, status);
    assertEquals(17, row.valueAt(0));
    assertEquals(0, allocated, "warmed universal JOIN advance allocated " + allocated);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }

  private static void createDescriptorTables(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE descriptor_left (tenant INTEGER,code VARCHAR(12),day DATE,"
            + "amount DECIMAL(10,2),score REAL,ratio DOUBLE PRECISION,marker BIGINT,"
            + "PRIMARY KEY(tenant,code))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE descriptor_right (tenant INTEGER,code VARCHAR(12),day DATE,"
            + "amount DECIMAL(10,2),score REAL,ratio DOUBLE PRECISION,label VARCHAR(16),"
            + "PRIMARY KEY(tenant,code))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO descriptor_left VALUES "
            + "(7,'alpha',DATE '2025-02-03',12.50,1.25,-2.5,101),"
            + "(8,'orphan',DATE '2025-02-04',20.00,3.5,4.75,202)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO descriptor_right VALUES "
            + "(7,'alpha',DATE '2025-02-03',12.50,1.25,-2.5,'matched'),"
            + "(8,'orphan',DATE '2025-02-05',20.00,3.5,4.75,'wrong-day')", result));
  }

  private static void insertScaleRows(
      SqlSession session, SqlExecutionResult result, String table) {
    StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" VALUES ");
    for (int item = 1; item <= 32; item++) {
      if (item > 1) sql.append(',');
      sql.append("(1,").append(item).append(',').append(item).append(')');
    }
    assertEquals(StatusCode.OK, session.execute(sql.toString(), result));
  }

  private static void assertAnalyzeAccess(
      SqlSession session,
      SqlExecutionResult result,
      String query,
      String expected,
      long candidates) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN ANALYZE " + query, cursor));
    boolean found = false;
    boolean root = true;
    while (session.nextScan(cursor, row) == StatusCode.OK) {
      if (root) {
        root = false;
        continue;
      }
      if (row.valueAt(0) != PackedText.pack(expected)) continue;
      assertEquals(candidates, row.valueAt(2));
      found = true;
      break;
    }
    assertTrue(found, expected);
    while (session.nextScan(cursor, row) == StatusCode.OK) { }
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertAnalyzeRootRange(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "EXPLAIN ANALYZE SELECT a.quantity,b.marker FROM stock_keys a "
            + "JOIN stock_probe b ON a.warehouse=b.warehouse AND a.item=b.item "
            + "WHERE a.warehouse=1 AND a.item>=30",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("primary"), row.valueAt(0));
    assertEquals(3, row.valueAt(2));
    while (session.nextScan(cursor, row) == StatusCode.OK) { }
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertLeftMissing(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a.item,b.marker FROM stock_keys a LEFT JOIN stock_probe b "
            + "ON a.warehouse=b.warehouse AND a.item=b.item WHERE a.item=33",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(33, row.valueAt(0));
    assertTrue(row.isNull(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertDescriptorReopenDoesNotAllocate(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a.quantity,b.quantity FROM stock_keys a JOIN stock_keys b "
            + "ON a.warehouse=b.warehouse AND a.item=b.item",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    ThreadMXBean bean = allocationBean();
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    StatusCode status = session.nextScan(cursor, row);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    allocationGuard += row.valueAt(0);
    assertEquals(StatusCode.OK, status);
    assertEquals(0, allocated, "warmed descriptor index reopen allocated " + allocated);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertRows(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      long[] expected,
      String[] text) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    for (int column = 0; column < expected.length; column++) {
      assertEquals(expected[column], row.valueAt(column));
    }
    if (text != null) assertText(row, expected.length, text[0]);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertText(SqlScanRowResult row, int column, String expected) {
    char[] actual = new char[expected.length()];
    assertEquals(expected.length(), row.copyTextAt(column, actual, 0));
    assertEquals(expected, new String(actual));
  }

  private static long scalarCount(
      SqlSession session, SqlExecutionResult result, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    long value = row.valueAt(0);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    return value;
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        root, DATABASE, WalGeneration.of(1), 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
