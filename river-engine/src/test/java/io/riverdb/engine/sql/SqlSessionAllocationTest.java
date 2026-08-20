package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSessionAllocationTest {
  private static final String JOIN_BLOCK_PIPELINE =
      "SELECT rendered,label FROM (SELECT CAST(tv.observed AS VARCHAR(32)) "
          + "AS rendered,texts.label AS label FROM temporal_values tv "
          + "JOIN texts ON tv.id=texts.id WHERE tv.id=1) joined";
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
        session.execute(
            "CREATE TABLE raw_texts "
                + "(id BIGINT PRIMARY KEY, label VARCHAR(7) NOT NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO raw_texts VALUES (2, 'alpha')", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX texts_label ON texts(label)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE exact_values "
                + "(id BIGINT PRIMARY KEY, amount DECIMAL(8,2), enabled BOOLEAN)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO exact_values VALUES (1, 42.70, TRUE)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX exact_values_amount ON exact_values(amount)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE temporal_values ("
                + "id BIGINT PRIMARY KEY, day DATE, alarm TIME(3), "
                + "observed TIMESTAMP(6), captured TIMESTAMP(6) WITH TIME ZONE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO temporal_values VALUES ("
                + "1, DATE '1969-12-31', TIME '01:02:03.456', "
                + "TIMESTAMP '1969-12-31 23:59:59.123456', "
                + "TIMESTAMP WITH TIME ZONE '1970-01-01 01:30:00+01:30')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE checked_values (id BIGINT PRIMARY KEY, day DATE "
                + "CHECK (EXTRACT(DAY FROM day)>=10))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO checked_values VALUES (1,DATE '2024-02-10')", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW regional AS "
                + "SELECT id, balance, region FROM t WHERE balance=10",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW temporal_derived AS SELECT id,next_day FROM "
                + "(SELECT id,day+1 AS next_day FROM temporal_values) q",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT next_day FROM temporal_derived WHERE id=1 "
                + "AND next_day=DATE '1970-01-01'",
            result));
    assertEquals(0, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT day, alarm, observed, captured FROM temporal_values "
                + "WHERE id=1 AND day=DATE '1969-12-31' "
                + "AND captured=TIMESTAMP WITH TIME ZONE "
                + "'1970-01-01 00:00:00+00:00'",
            result));
    assertEquals(-1, result.valueAt(0));
    assertEquals(3_723_456_000L, result.valueAt(1));
    assertEquals(-876_544, result.valueAt(2));
    assertEquals(0, result.valueAt(3));
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.time(3), result.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.timestamp(6), result.typeDescriptorAt(2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), result.typeDescriptorAt(3));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT EXTRACT(DAY FROM DATE '2024-02-28'+1)", result));
    assertEquals(29, result.value());
    assertEquals(SqlTypeDescriptor.BIGINT, result.typeDescriptorAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT day FROM temporal_values WHERE id=1 "
                + "AND day+0 IN (DATE '1969-12-31',NULL)",
            result));
    assertEquals(-1, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(EXTRACT(SECOND FROM observed)) FROM temporal_values",
            result));
    assertEquals(1, result.valueAt(0));
    assertEquals(SqlTypeDescriptor.BIGINT, result.typeDescriptorAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(EXTRACT(SECOND FROM observed)) FROM temporal_values "
                + "WHERE CAST(alarm AS TIME(6)) BETWEEN "
                + "TIME '01:02:03.4' AND TIME '01:02:03.500000'",
            result));
    assertEquals(1, result.valueAt(0));
    assertEquals(SqlTypeDescriptor.BIGINT, result.typeDescriptorAt(0));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult scanRow = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM temporal_values WHERE EXTRACT(DAY FROM day)=31",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(1, scanRow.valueAt(0));
    assertEquals(SqlTypeDescriptor.BIGINT, scanRow.typeDescriptorAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, day+1 AS key_day FROM temporal_values ORDER BY key_day",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(1, scanRow.valueAt(0));
    assertEquals(0, scanRow.valueAt(1));
    assertEquals(SqlTypeDescriptor.DATE, scanRow.typeDescriptorAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT alarm, observed, captured FROM temporal_values "
                + "WHERE alarm BETWEEN TIME '01:02:03.4' AND TIME '01:02:03.500000' "
                + "AND observed IN (TIMESTAMP '1969-12-31 23:59:59.123', "
                + "TIMESTAMP '1969-12-31 23:59:59.123456') "
                + "AND captured BETWEEN TIMESTAMP WITH TIME ZONE "
                + "'1969-12-31 23:59:59.999999+00:00' AND "
                + "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00.000001+00:00'",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(3_723_456_000L, scanRow.valueAt(0));
    assertEquals(-876_544, scanRow.valueAt(1));
    assertEquals(0, scanRow.valueAt(2));
    assertEquals(SqlTypeDescriptor.time(3), scanRow.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.timestamp(6), scanRow.typeDescriptorAt(1));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), scanRow.typeDescriptorAt(2));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT day, COUNT(*) FROM temporal_values "
                + "WHERE EXTRACT(DAY FROM day)=31 GROUP BY day",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(-1, scanRow.valueAt(0));
    assertEquals(1, scanRow.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT DISTINCT day FROM temporal_values "
                + "WHERE EXTRACT(DAY FROM day)=31",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(-1, scanRow.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, COUNT(EXTRACT(DAY FROM observed)) FROM temporal_values "
                + "GROUP BY id",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(1, scanRow.valueAt(0));
    assertEquals(1, scanRow.valueAt(1));
    assertEquals(SqlTypeDescriptor.BIGINT, scanRow.typeDescriptorAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    ParameterSet pointParameters = new ParameterSet(2, 0);
    assertEquals(
        StatusCode.OK,
        pointParameters.appendFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(
        StatusCode.OK,
        pointParameters.appendFixed(SqlTypeDescriptor.BIGINT, 7));
    ParameterSet scanParameters = new ParameterSet(1, 0);
    assertEquals(
        StatusCode.OK,
        scanParameters.appendFixed(SqlTypeDescriptor.BIGINT, 7));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, session.beginScan(JOIN_BLOCK_PIPELINE, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(26, scanRow.textLengthAt(0));
    assertEquals(5, scanRow.textLengthAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    for (int index = 0; index < 100; index++) {
      exercise(session, result);
      exerciseCount(session, result);
      exerciseText(session, result);
      exerciseExactPoint(session, result);
      exerciseTemporalPoint(session, result);
      exerciseTemporalScalar(session, result);
      exerciseTemporalProjection(session, result);
      exerciseTemporalViewPoint(session, result);
      exerciseTemporalPredicatePoint(session, result);
      exerciseTemporalAggregateExpression(session, result);
      exerciseCheckUpdate(session, result);
      exerciseMutationExpressions(session, result);
      exerciseTypedPoint(session, pointParameters, result);
    }
    long threadId = Thread.currentThread().threadId();
    long joinBefore = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exerciseJoinBlockPipeline(session, cursor, scanRow, result);
    }
    long joinAllocated = bean.getThreadAllocatedBytes(threadId) - joinBefore;
    assertTrue(
        joinAllocated <= 512,
        "warmed JOIN block pipeline allocated bytes: " + joinAllocated);
    long before = bean.getThreadAllocatedBytes(threadId);
    for (int index = 0; index < 100; index++) {
      exercise(session, result);
      exerciseCount(session, result);
      exerciseText(session, result);
      exerciseExactPoint(session, result);
      exerciseTemporalPoint(session, result);
      exerciseTemporalScalar(session, result);
      exerciseTemporalProjection(session, result);
      exerciseTemporalViewPoint(session, result);
      exerciseTemporalPredicatePoint(session, result);
      exerciseTemporalAggregateExpression(session, result);
      exerciseCheckUpdate(session, result);
      exerciseMutationExpressions(session, result);
      exerciseTypedPoint(session, pointParameters, result);
    }
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;
    assertTrue(allocated <= 512, "warmed SQL point select allocated bytes: " + allocated);

    for (int index = 0; index < 100; index++) {
      exerciseScan(session, cursor, scanRow, result);
      exerciseSort(session, cursor, scanRow, result);
      exerciseScalar(session, cursor, scanRow, result);
      exerciseExists(session, cursor, scanRow, result);
      exerciseCorrelatedMembership(session, cursor, scanRow, result);
      exerciseRecursiveExists(session, cursor, scanRow, result);
      exerciseAggregate(session, cursor, scanRow, result);
      exerciseTextAggregate(session, cursor, scanRow, result);
      exerciseBlockPipeline(session, cursor, scanRow, result);
      exerciseTextBlockPipeline(session, cursor, scanRow, result);
      exerciseJoin(session, cursor, scanRow, result);
      exerciseUnindexedJoin(session, cursor, scanRow, result);
      exerciseComputedTextJoin(session, cursor, scanRow, result);
      exerciseLeftJoin(session, cursor, scanRow, result);
      exerciseDisjunction(session, cursor, scanRow, result);
      exerciseView(session, cursor, scanRow, result);
      exerciseExplain(session, cursor, scanRow, result);
      exerciseExactScan(session, cursor, scanRow, result);
      exerciseTemporalScan(session, cursor, scanRow, result);
      exerciseTemporalProjectionScan(session, cursor, scanRow, result);
      exerciseTemporalComputedKey(session, cursor, scanRow, result);
      exerciseTemporalViewScan(session, cursor, scanRow, result);
      exerciseTemporalPredicateScan(session, cursor, scanRow, result);
      exerciseTemporalPredicateGroup(session, cursor, scanRow, result);
      exerciseTemporalPredicateDistinct(session, cursor, scanRow, result);
      exerciseTemporalGroupedAggregateExpression(session, cursor, scanRow, result);
      exerciseTypedScan(session, scanParameters, cursor, scanRow, result);
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
      exerciseTextAggregate(session, cursor, scanRow, result);
      exerciseBlockPipeline(session, cursor, scanRow, result);
      exerciseTextBlockPipeline(session, cursor, scanRow, result);
      exerciseJoin(session, cursor, scanRow, result);
      exerciseUnindexedJoin(session, cursor, scanRow, result);
      exerciseComputedTextJoin(session, cursor, scanRow, result);
      exerciseLeftJoin(session, cursor, scanRow, result);
      exerciseDisjunction(session, cursor, scanRow, result);
      exerciseView(session, cursor, scanRow, result);
      exerciseExplain(session, cursor, scanRow, result);
      exerciseExactScan(session, cursor, scanRow, result);
      exerciseTemporalScan(session, cursor, scanRow, result);
      exerciseTemporalProjectionScan(session, cursor, scanRow, result);
      exerciseTemporalComputedKey(session, cursor, scanRow, result);
      exerciseTemporalViewScan(session, cursor, scanRow, result);
      exerciseTemporalPredicateScan(session, cursor, scanRow, result);
      exerciseTemporalPredicateGroup(session, cursor, scanRow, result);
      exerciseTemporalPredicateDistinct(session, cursor, scanRow, result);
      exerciseTemporalGroupedAggregateExpression(session, cursor, scanRow, result);
      exerciseTypedScan(session, scanParameters, cursor, scanRow, result);
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

  private static void exerciseExactPoint(
      SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT amount FROM exact_values "
            + "WHERE id=1 AND amount=42.700 AND enabled=TRUE",
        result).ordinal();
    allocationGuard += result.value();
  }

  private static void exerciseTemporalPoint(
      SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT day, alarm, observed, captured FROM temporal_values "
            + "WHERE id=1 AND day=DATE '1969-12-31' "
            + "AND captured=TIMESTAMP WITH TIME ZONE "
            + "'1970-01-01 00:00:00+00:00'",
        result).ordinal();
    allocationGuard += result.valueAt(0);
    allocationGuard += result.valueAt(1);
    allocationGuard += result.valueAt(2);
    allocationGuard += result.valueAt(3);
  }

  private static void exerciseTemporalScalar(
      SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT EXTRACT(DAY FROM DATE '2024-02-28'+1)", result).ordinal();
    allocationGuard += result.value();
  }

  private static void exerciseTemporalProjection(
      SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT EXTRACT(DAY FROM observed), CAST(observed AS VARCHAR(26)) "
            + "FROM temporal_values WHERE id=1",
        result).ordinal();
    allocationGuard += result.valueAt(0);
    allocationGuard += result.textLengthAt(1);
  }

  private static void exerciseTemporalViewPoint(
      SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT next_day FROM temporal_derived WHERE id=1 "
            + "AND next_day=DATE '1970-01-01'",
        result).ordinal();
    allocationGuard += result.valueAt(0);
  }

  private static void exerciseTemporalPredicatePoint(
      SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT day FROM temporal_values WHERE id=1 "
            + "AND day+0 IN (DATE '1969-12-31',NULL)",
        result).ordinal();
    allocationGuard += result.valueAt(0);
  }

  private static void exerciseTemporalAggregateExpression(
      SqlSession session, SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT COUNT(EXTRACT(SECOND FROM observed)) FROM temporal_values "
            + "WHERE CAST(alarm AS TIME(6)) BETWEEN "
            + "TIME '01:02:03.4' AND TIME '01:02:03.500000'",
        result).ordinal();
    allocationGuard += result.valueAt(0);
  }

  private static void exerciseCheckUpdate(
      SqlSession session, SqlExecutionResult result) {
    StatusCode status = session.execute(
        "UPDATE checked_values SET day=DATE '2024-02-01' WHERE id=1",
        result);
    assertEquals(StatusCode.CHECK_VIOLATION, status);
    allocationGuard += status.ordinal();
    assertEquals(0, result.affectedRows());
    allocationGuard += result.affectedRows();
  }

  private static void exerciseMutationExpressions(
      SqlSession session, SqlExecutionResult result) {
    StatusCode status = session.execute(
        "UPDATE checked_values SET day=day+0 WHERE id+0=999",
        result);
    assertEquals(StatusCode.OK, status);
    allocationGuard += status.ordinal();
    assertEquals(0, result.affectedRows());
    allocationGuard += result.affectedRows();
    status = session.execute(
        "INSERT INTO exact_values VALUES(1+0,20.00+0.00,FALSE)", result);
    assertEquals(StatusCode.CONFLICT, status);
    allocationGuard += status.ordinal();
    assertEquals(0, result.affectedRows());
    allocationGuard += result.affectedRows();
    status = session.execute("DELETE FROM exact_values WHERE id+0=999", result);
    assertEquals(StatusCode.OK, status);
    allocationGuard += status.ordinal();
    assertEquals(0, result.affectedRows());
    allocationGuard += result.affectedRows();
  }

  private static void exerciseTypedPoint(
      SqlSession session,
      ParameterSet parameters,
      SqlExecutionResult result) {
    allocationGuard += session.execute(
        "SELECT region FROM t WHERE id=? AND region=?", parameters, result).ordinal();
    allocationGuard += result.value();
  }

  private static void exerciseTypedScan(
      SqlSession session,
      ParameterSet parameters,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT id FROM t WHERE region=?", parameters, cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseExactScan(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT amount FROM exact_values "
            + "WHERE amount BETWEEN 40.0 AND 50.000",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTemporalScan(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT alarm, observed, captured FROM temporal_values "
            + "WHERE alarm BETWEEN TIME '01:02:03.4' AND TIME '01:02:03.500000' "
            + "AND observed IN (TIMESTAMP '1969-12-31 23:59:59.123', "
            + "TIMESTAMP '1969-12-31 23:59:59.123456') "
            + "AND captured BETWEEN TIMESTAMP WITH TIME ZONE "
            + "'1969-12-31 23:59:59.999999+00:00' AND "
            + "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00.000001+00:00'",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += row.valueAt(1);
    allocationGuard += row.valueAt(2);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTemporalProjectionScan(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT EXTRACT(DAY FROM observed), CAST(observed AS VARCHAR(26)) "
            + "FROM temporal_values ORDER BY id DESC",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += row.textLengthAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTemporalComputedKey(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT id, day+1 AS key_day FROM temporal_values ORDER BY key_day",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTemporalViewScan(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT next_day FROM temporal_derived WHERE "
            + "next_day>=DATE '0001-01-01' ORDER BY next_day",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTemporalPredicateScan(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT id FROM temporal_values WHERE EXTRACT(DAY FROM day)=31",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTemporalPredicateGroup(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT day, COUNT(*) FROM temporal_values "
            + "WHERE EXTRACT(DAY FROM day)=31 GROUP BY day",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTemporalPredicateDistinct(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT DISTINCT day FROM temporal_values "
            + "WHERE EXTRACT(DAY FROM day)=31",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTemporalGroupedAggregateExpression(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT day, MAX(CAST(observed AS TIMESTAMP(6))) FROM temporal_values "
            + "GROUP BY day HAVING "
            + "EXTRACT(YEAR FROM MAX(CAST(observed AS TIMESTAMP(6)))) >= 1970",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
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
            + "GROUP BY region HAVING ABS(SUM(balance))+1 >= 11 "
            + "AND MIN(balance)=10 ORDER BY region",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += row.valueAt(1);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTextAggregate(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT MIN(label) FROM texts "
            + "HAVING MIN(label)='alpha' AND MAX(label)='alpha'",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.textLengthAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseBlockPipeline(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT total FROM (SELECT SUM(balance) AS total FROM t "
            + "HAVING MIN(region)=7) aggregate_stage",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.valueAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseTextBlockPipeline(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT minimum FROM (SELECT MIN(label) AS minimum FROM texts "
            + "HAVING MAX(label)='alpha') text_stage",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.textLengthAt(0);
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += session.closeScan(cursor, result).ordinal();
  }

  private static void exerciseJoinBlockPipeline(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(JOIN_BLOCK_PIPELINE, cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.textLengthAt(0);
    allocationGuard += row.textLengthAt(1);
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

  private static void exerciseComputedTextJoin(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      SqlExecutionResult result) {
    allocationGuard += cursor.reset().ordinal();
    allocationGuard += session.beginScan(
        "SELECT texts.label,raw_texts.label FROM texts JOIN raw_texts "
            + "ON texts.label=raw_texts.label OR texts.id+1=raw_texts.id",
        cursor).ordinal();
    allocationGuard += session.nextScan(cursor, row).ordinal();
    allocationGuard += row.textLengthAt(0);
    allocationGuard += row.textLengthAt(1);
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
