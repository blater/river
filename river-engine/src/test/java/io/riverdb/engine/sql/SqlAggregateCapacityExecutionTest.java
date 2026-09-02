package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.engine.runtime.SqlDatabaseRuntime;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlQuery;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlAggregateCapacityExecutionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4147475245474154L, 0x4543415041434954L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void executesAggregateBoundariesAfterReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = open(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE aggregate_capacity (id BIGINT PRIMARY KEY,value BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO aggregate_capacity VALUES (1,7)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 7, opened));
    database = opened.database();
    session = open(database);
    int[] boundaries = {8, 9, 63, 64, 65, SqlShapeLimits.MAX_AGGREGATES};
    for (int count : boundaries) assertAggregateCount(session, result, count);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void wideAggregateAdmissionFailsAtomicallyAtShapeBudget(@TempDir Path root)
      throws IOException {
    RiverRuntimeConfig.Result config = new RiverRuntimeConfig.Result();
    assertEquals(StatusCode.OK, RiverRuntimeConfig.load(
        root, 64_000_000L, root.toString(), config, new StatusDetail(256)));
    SqlDatabaseRuntime.OpenResult opened = new SqlDatabaseRuntime.OpenResult();
    assertEquals(StatusCode.OK, SqlDatabaseRuntime.create(
        config.config(), root, DATABASE, opened, new StatusDetail(256)));
    SqlRuntimeLeaseResult acquired = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, opened.runtime().acquire(acquired));
    SqlRuntimeLease lease = acquired.lease();
    assertEquals(StatusCode.OK,
        lease.reserve(opened.runtime().sessionShapeCacheBudgetBytes() - 1));
    BoundSqlStatement bound = new BoundSqlStatement(new SqlSessionShapeBudget(lease));
    SqlCommand command = new SqlCommand();
    SqlQuery query = new SqlQuery();
    assertEquals(StatusCode.OK, new SqlParser().parseQuery(
        "SELECT a,b,c,d,e,f,g,h,i,COUNT(*) FROM t "
            + "GROUP BY a,b,c,d,e,f,g,h,i",
        query,
        command));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        SqlBlockShapeAdmission.reserve(command, bound, command.type()));
    assertEquals(0, bound.projectedTypeDescriptors.length);
    assertEquals(StatusCode.OK, lease.close());
    assertEquals(StatusCode.OK, opened.runtime().prepareClose());
    assertEquals(StatusCode.OK, opened.runtime().completeClose());
  }

  @Test
  void expressionNodeAdmissionReturnsStatusBeforeProgramReads(@TempDir Path root)
      throws IOException {
    RiverRuntimeConfig.Result config = new RiverRuntimeConfig.Result();
    assertEquals(StatusCode.OK, RiverRuntimeConfig.load(
        root, 64_000_000L, root.toString(), config, new StatusDetail(256)));
    SqlDatabaseRuntime.OpenResult opened = new SqlDatabaseRuntime.OpenResult();
    assertEquals(StatusCode.OK, SqlDatabaseRuntime.create(
        config.config(), root, DATABASE, opened, new StatusDetail(256)));
    assertExpressionBudgetFailure(opened.runtime(), "SELECT a FROM t");
    assertExpressionBudgetFailure(opened.runtime(), "SELECT a+1 FROM t");
    assertEquals(StatusCode.OK, opened.runtime().prepareClose());
    assertEquals(StatusCode.OK, opened.runtime().completeClose());
  }

  @Test
  void groupsAndOrdersByCompleteTuple(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = open(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE tuple_groups (id BIGINT PRIMARY KEY,a BIGINT,b BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO tuple_groups VALUES (1,1,2),(2,1,1),(3,1,2),(4,2,1)", result));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a,b,COUNT(*) FROM tuple_groups GROUP BY a,b ORDER BY a,b", cursor));
    assertTuple(session, cursor, row, 1, 1, 1);
    assertTuple(session, cursor, row, 1, 2, 2);
    assertTuple(session, cursor, row, 2, 1, 1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a,COUNT(DISTINCT b) FROM tuple_groups GROUP BY a ORDER BY a", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(2, row.valueAt(1));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT id,a,b FROM tuple_groups ORDER BY a,b DESC", cursor));
    long[] ids = {1, 3, 2, 4};
    for (long id : ids) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(id, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT DISTINCT a,b FROM tuple_groups ORDER BY a DESC,b", cursor));
    assertTuple(session, cursor, row, 2, 1);
    assertTuple(session, cursor, row, 1, 1);
    assertTuple(session, cursor, row, 1, 2);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a,b,COUNT(*),SUM(id),MIN(id),MAX(id) FROM tuple_groups "
            + "GROUP BY a,b ORDER BY a,b", cursor));
    assertAggregateTuple(session, cursor, row, 1, 1, 1, 2, 2, 2);
    assertAggregateTuple(session, cursor, row, 1, 2, 2, 4, 1, 3);
    assertAggregateTuple(session, cursor, row, 2, 1, 1, 4, 4, 4);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*),SUM(id) FROM tuple_groups GROUP BY a,b", cursor));
    assertTuple(session, cursor, row, 1, 2);
    assertTuple(session, cursor, row, 2, 4);
    assertTuple(session, cursor, row, 1, 4);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT SUM(id),COUNT(*) FROM tuple_groups GROUP BY a,b "
            + "HAVING SUM(id)>3 AND COUNT(*)>=1 ORDER BY a,b", cursor));
    assertTuple(session, cursor, row, 4, 2);
    assertTuple(session, cursor, row, 4, 1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a,b,COUNT(*) FROM tuple_groups GROUP BY a,b "
            + "HAVING b=2 ORDER BY a,b", cursor));
    assertTuple(session, cursor, row, 1, 2, 2);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*) FROM tuple_groups GROUP BY a,b HAVING b=2 ORDER BY a,b", cursor));
    assertTuple(session, cursor, row, 2);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT b,a,COUNT(*) FROM tuple_groups GROUP BY a,b "
            + "HAVING a=1 ORDER BY a,b", cursor));
    assertTuple(session, cursor, row, 1, 1, 1);
    assertTuple(session, cursor, row, 2, 1, 2);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a FROM tuple_groups GROUP BY a,b ORDER BY a,b", cursor));
    assertTuple(session, cursor, row, 1);
    assertTuple(session, cursor, row, 1);
    assertTuple(session, cursor, row, 2);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a+1 AS shifted,a+1 AS repeated,COUNT(*),SUM(id+1) "
            + "FROM tuple_groups GROUP BY a+1 HAVING repeated=2 ORDER BY shifted", cursor));
    assertComputedTuple(session, cursor, row, 2, 3, 9);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT a+1 AS shifted FROM tuple_groups GROUP BY a+1 ORDER BY shifted", cursor));
    assertTuple(session, cursor, row, 2);
    assertTuple(session, cursor, row, 3);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void aggregatesJoinRowsOnceAndPublishesEverySelectedAggregate(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = open(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE stock (i_id BIGINT PRIMARY KEY)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE order_line (ol_i_id BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO stock VALUES (1),(2)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO order_line VALUES (1),(1),(2),(9)", result));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(DISTINCT s.i_id), SUM(s.i_id) FROM order_line ol "
            + "INNER JOIN stock s ON ol.ol_i_id=s.i_id", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.valueAt(0));
    assertEquals(4, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*) AS n,SUM(s.i_id) AS total FROM order_line ol "
            + "INNER JOIN stock s ON ol.ol_i_id=s.i_id ORDER BY n DESC LIMIT 1",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(3, row.valueAt(0));
    assertEquals(4, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT s.i_id,COUNT(*) AS n,SUM(s.i_id) AS total FROM order_line ol "
            + "INNER JOIN stock s ON ol.ol_i_id=s.i_id GROUP BY s.i_id "
            + "ORDER BY n DESC LIMIT 1",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(2, row.valueAt(1));
    assertEquals(2, row.valueAt(2));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT s.i_id,ol.ol_i_id,COUNT(DISTINCT s.i_id),SUM(s.i_id) "
            + "FROM order_line ol INNER JOIN stock s ON ol.ol_i_id=s.i_id "
            + "GROUP BY s.i_id,ol.ol_i_id", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(1, row.valueAt(2));
    assertEquals(2, row.valueAt(3));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.valueAt(0));
    assertEquals(2, row.valueAt(1));
    assertEquals(1, row.valueAt(2));
    assertEquals(2, row.valueAt(3));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE join_group_left (id BIGINT PRIMARY KEY,a INTEGER)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE join_group_right (left_id BIGINT,b BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO join_group_left VALUES (1,10),(2,10),(3,20)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO join_group_right VALUES (1,100),(1,200),(2,100),(3,100)", result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT r.b AS rb,l.a AS la,COUNT(*) FROM join_group_left l "
            + "JOIN join_group_right r ON l.id=r.left_id GROUP BY l.a,r.b "
            + "HAVING la>=10 ORDER BY la,rb", cursor));
    assertEquals(SqlTypeDescriptor.BIGINT, session.scanColumnTypeDescriptor(cursor, 0));
    assertEquals(SqlTypeDescriptor.INTEGER, session.scanColumnTypeDescriptor(cursor, 1));
    assertTuple(session, cursor, row, 100, 10, 2);
    assertTuple(session, cursor, row, 200, 10, 1);
    assertTuple(session, cursor, row, 100, 20, 1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*) FROM join_group_left l "
            + "JOIN join_group_right r ON l.id=r.left_id GROUP BY l.a,r.b "
            + "HAVING b=100", cursor));
    assertTuple(session, cursor, row, 2);
    assertTuple(session, cursor, row, 1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO join_group_right VALUES (1,200),(1,200)", result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*) FROM join_group_left l "
            + "JOIN join_group_right r ON l.id=r.left_id GROUP BY l.a,r.b "
            + "ORDER BY a DESC,b ASC", cursor));
    assertTuple(session, cursor, row, 1);
    assertTuple(session, cursor, row, 2);
    assertTuple(session, cursor, row, 3);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*) FROM join_group_left l "
            + "JOIN join_group_right r ON l.id=r.left_id GROUP BY l.a,r.b "
            + "ORDER BY a ASC,b DESC LIMIT 2", cursor));
    assertTuple(session, cursor, row, 3);
    assertTuple(session, cursor, row, 2);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT s.i_id,COUNT(*) FROM order_line ol INNER JOIN stock s "
            + "ON ol.ol_i_id=s.i_id GROUP BY s.i_id LIMIT 1", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*) FROM order_line ol INNER JOIN stock s "
            + "ON ol.ol_i_id=s.i_id LIMIT 0", cursor));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(DISTINCT s.i_id),SUM(s.i_id) FROM order_line ol "
            + "INNER JOIN stock s ON ol.ol_i_id=s.i_id WHERE ol.ol_i_id=99", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(0, row.valueAt(0));
    org.junit.jupiter.api.Assertions.assertTrue(row.isNull(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void admitsWideGroupedJoinOutputs(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = open(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(wideGroupTable(), result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE wide_group_link (id BIGINT PRIMARY KEY,left_id BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(wideGroupInsert(), result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO wide_group_link VALUES (1,1)", result));
    for (int columns : new int[] {8, 9, 64}) {
      SqlScanCursor cursor = new SqlScanCursor();
      SqlScanRowResult row = new SqlScanRowResult();
      assertEquals(StatusCode.OK, session.beginScan(wideGroupQuery(columns), cursor));
      assertEquals(columns + 1, cursor.projectedColumnCount());
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      for (int column = 0; column < columns; column++) {
        assertEquals(column, row.valueAt(column));
      }
      assertEquals(1, row.valueAt(columns));
      assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
      assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    }
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void scalarJoinAggregateStreamsBeyondTheBlockStoreRowLimit(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = open(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE join_many_left (id INTEGER,bucket INTEGER)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE join_many_right (id INTEGER,bucket INTEGER)", result));
    insertJoinMany(session, result, "join_many_left", 1_000);
    insertJoinMany(session, result, "join_many_right", 0);

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*),SUM(r.id) FROM join_many_left l "
            + "JOIN join_many_right r ON l.bucket=r.bucket",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(66_049, row.valueAt(0));
    assertEquals(8_520_321, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT SUM(r.id),COUNT(*) FROM join_many_left l "
            + "JOIN join_many_right r ON l.bucket=r.bucket",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(8_520_321, row.valueAt(0));
    assertEquals(66_049, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(r.id),SUM(r.id) FROM join_many_left l "
            + "JOIN join_many_right r ON l.bucket=r.bucket",
        cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(66_049, row.valueAt(0));
    assertEquals(8_520_321, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void distinctTupleSpillsAndReplaysAfterReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = open(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE tuple_spill (id BIGINT PRIMARY KEY,a BIGINT,b BIGINT)", result));
    for (int first = 1; first <= 1_025; first += 64) {
      assertEquals(StatusCode.OK, session.execute(insert(first), result));
    }
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 7, opened));
    database = opened.database();
    session = open(database);
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT DISTINCT a,b FROM tuple_spill ORDER BY a,b", cursor));
    for (int a = 0; a < 5; a++) {
      for (int b = 0; b < 2; b++) assertTuple(session, cursor, row, a, b);
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*) FROM tuple_spill GROUP BY a,b HAVING b=1 ORDER BY a,b", cursor));
    long[] counts = {103, 103, 102, 103, 102};
    for (long count : counts) assertTuple(session, cursor, row, count);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void insertJoinMany(
      SqlSession session, SqlExecutionResult result, String table, int idOffset) {
    for (int first = 1; first <= 257; first += 64) {
      int last = Math.min(257, first + 63);
      StringBuilder sql = new StringBuilder("INSERT INTO ")
          .append(table).append(" VALUES ");
      for (int value = first; value <= last; value++) {
        if (value > first) sql.append(',');
        sql.append('(').append(value + idOffset).append(",1)");
      }
      assertEquals(StatusCode.OK, session.execute(sql.toString(), result));
    }
  }

  @Test
  void hiddenNullableAndTextGroupKeysDriveHaving(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = open(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE tuple_text (id BIGINT PRIMARY KEY,a BIGINT,b VARCHAR(10))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO tuple_text VALUES "
            + "(1,NULL,'猫'),(2,NULL,'猫'),(3,1,NULL),(4,2,'犬')", result));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "SELECT COUNT(*) FROM tuple_text GROUP BY a,b "
            + "HAVING a IS NULL AND b='猫'", cursor));
    assertTuple(session, cursor, row, 2);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertAggregateCount(
      SqlSession session, SqlExecutionResult result, int count) {
    assertEquals(StatusCode.OK, session.execute(query(count), result));
    assertEquals(1, result.valueAt(0));
  }

  private static String query(int count) {
    StringBuilder sql = new StringBuilder(
        "SELECT COUNT(value+0) FROM aggregate_capacity");
    if (count > 1) sql.append(" HAVING ");
    for (int invocation = 1; invocation < count; invocation++) {
      if (invocation > 1) sql.append(" AND ");
      sql.append("COUNT(value+").append(invocation).append(")=1");
    }
    return sql.toString();
  }

  private static String insert(int first) {
    StringBuilder sql = new StringBuilder("INSERT INTO tuple_spill VALUES ");
    int last = Math.min(1_025, first + 63);
    for (int id = first; id <= last; id++) {
      if (id > first) sql.append(',');
      sql.append('(').append(id).append(',').append(id % 5).append(',')
          .append(id % 2).append(')');
    }
    return sql.toString();
  }

  private static String wideGroupTable() {
    StringBuilder sql = new StringBuilder(
        "CREATE TABLE wide_group_left (id BIGINT PRIMARY KEY");
    for (int column = 0; column < 64; column++) {
      sql.append(",c").append(column).append(" BIGINT");
    }
    return sql.append(')').toString();
  }

  private static void assertExpressionBudgetFailure(
      SqlDatabaseRuntime runtime, String sql) {
    SqlRuntimeLeaseResult acquired = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, runtime.acquire(acquired));
    SqlRuntimeLease lease = acquired.lease();
    BoundSqlStatement bound = new BoundSqlStatement(new SqlSessionShapeBudget(lease));
    assertEquals(StatusCode.OK, bound.reserveProjectionColumns(1));
    long remaining = runtime.sessionShapeCacheBudgetBytes() - lease.reservedBytes() - 1;
    assertEquals(StatusCode.OK, lease.reserve(remaining));
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK,
        new SqlParser().parseQuery(sql, new SqlQuery(), command));
    SqlBlockSchema child = new SqlBlockSchema();
    child.set(1);
    child.setColumn(0, "a", SqlTypeDescriptor.BIGINT, false);
    bound.projectionPrograms.begin(1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        new SqlBlockExpressionBinder().bind(
            command, command.projectionExpression(0), 0, child, bound));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, bound.projectionPrograms.status());
    assertEquals(StatusCode.OK, lease.close());
  }

  private static String wideGroupInsert() {
    StringBuilder sql = new StringBuilder("INSERT INTO wide_group_left VALUES (1");
    for (int column = 0; column < 64; column++) sql.append(',').append(column);
    return sql.append(')').toString();
  }

  private static String wideGroupQuery(int columns) {
    StringBuilder sql = new StringBuilder("SELECT ");
    appendWideColumns(sql, columns, "l.");
    sql.append(",COUNT(*) FROM wide_group_left l JOIN wide_group_link r ")
        .append("ON l.id=r.left_id GROUP BY ");
    appendWideColumns(sql, columns, "l.");
    return sql.toString();
  }

  private static void appendWideColumns(
      StringBuilder sql, int columns, String qualifier) {
    for (int column = 0; column < columns; column++) {
      if (column > 0) sql.append(',');
      sql.append(qualifier).append('c').append(column);
    }
  }

  private static SqlSession open(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }

  private static void assertTuple(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      long value) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(value, row.valueAt(0));
  }

  private static void assertTuple(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      long first,
      long second,
      long count) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(first, row.valueAt(0));
    assertEquals(second, row.valueAt(1));
    assertEquals(count, row.valueAt(2));
  }

  private static void assertAggregateTuple(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      long first,
      long second,
      long count,
      long sum,
      long minimum,
      long maximum) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(first, row.valueAt(0));
    assertEquals(second, row.valueAt(1));
    assertEquals(count, row.valueAt(2));
    assertEquals(sum, row.valueAt(3));
    assertEquals(minimum, row.valueAt(4));
    assertEquals(maximum, row.valueAt(5));
  }

  private static void assertComputedTuple(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      long key,
      long count,
      long sum) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(key, row.valueAt(0));
    assertEquals(key, row.valueAt(1));
    assertEquals(count, row.valueAt(2));
    assertEquals(sum, row.valueAt(3));
  }

  private static void assertTuple(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      long first,
      long second) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(first, row.valueAt(0));
    assertEquals(second, row.valueAt(1));
  }
}
