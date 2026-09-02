package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorTupleIndexScanTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x545043435455504cL, 0x45494e4445583031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void compositeTpccShapesUseTupleIndexesAndPreserveOrder(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createSchema(session, result);
    insertRows(session, result);

    assertIndexPlan(session,
        "SELECT id FROM customers WHERE warehouse=1 AND district=2 AND last_name=7 "
            + "ORDER BY first_name,id", true);
    assertIndexPlan(session,
        "SELECT id FROM orders WHERE warehouse=1 AND district=2 AND customer=3 "
            + "ORDER BY order_number DESC LIMIT 1", true);
    assertIndexPlan(session,
        "SELECT id FROM new_orders WHERE warehouse=1 AND district=2 "
            + "ORDER BY order_number LIMIT 1", true);
    assertIndexPlan(session,
        "SELECT id FROM order_lines WHERE warehouse=1 AND district=2 AND order_number=9 "
            + "AND line_number>=2 AND line_number<4 ORDER BY line_number", true);
    assertIndexPlan(session,
        "SELECT id FROM stocks WHERE warehouse=1 AND item=2", false);

    assertRows(session,
        "SELECT id FROM orders WHERE warehouse=1 AND district=2 AND customer=3 "
            + "ORDER BY order_number DESC LIMIT 1", 12);
    assertRows(session,
        "SELECT line_number FROM order_lines WHERE warehouse=1 AND district=2 "
            + "AND order_number=9 AND line_number>=2 AND line_number<4 "
            + "ORDER BY line_number", 2, 3);
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE stocks SET quantity=99 WHERE warehouse=1 AND item=2", result));
    assertEquals(StatusCode.OK, session.execute(
        "SELECT quantity FROM stocks WHERE id=51", result));
    assertEquals(99, result.value());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void tupleIndexScanMergesPendingInsertAndDelete(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createSchema(session, result);
    insertRows(session, result);
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO orders VALUES (13,1,2,3,103)", result));
    assertRows(session,
        "SELECT id FROM orders WHERE warehouse=1 AND district=2 AND customer=3 "
            + "ORDER BY order_number DESC LIMIT 1", 13);
    assertEquals(StatusCode.OK, session.execute("DELETE FROM orders WHERE id=13", result));
    assertRows(session,
        "SELECT id FROM orders WHERE warehouse=1 AND district=2 AND customer=3 "
            + "ORDER BY order_number DESC LIMIT 1", 12);
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void tupleIndexScanRetainsAuthenticatedPinsAcrossLeafTransitions(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    execute(session, result,
        "CREATE TABLE entries (id BIGINT PRIMARY KEY,label VARCHAR(255))");
    execute(session, result, "CREATE INDEX entries_label ON entries(label,id)");
    String label = "p".repeat(255);
    execute(session, result, "BEGIN");
    for (int id = 1; id <= 200; id++) {
      execute(session, result,
          "INSERT INTO entries VALUES (" + id + ",'" + label + "')");
    }
    execute(session, result, "COMMIT");

    String forward = "SELECT id FROM entries WHERE label='" + label
        + "' ORDER BY label,id";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(forward, cursor));
    for (int id = 1; id <= 70; id++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(id, row.valueAt(0));
    }
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertSequentialRows(session, forward, 1, 200, 1);
    assertSequentialRows(session,
        "SELECT id FROM entries WHERE label='" + label
            + "' ORDER BY label DESC,id DESC", 200, 1, -1);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void serializableSecondaryRangeProtectsEveryMutationPoint(@TempDir Path root)
      throws Exception {
    RelationalDatabase database = create(root);
    SqlSession reader = session(database);
    SqlSession writer = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    execute(reader, result,
        "CREATE TABLE entries (id BIGINT PRIMARY KEY,bucket BIGINT,amount BIGINT)");
    execute(reader, result, "CREATE INDEX entries_bucket ON entries(bucket)");
    execute(reader, result, "INSERT INTO entries VALUES (1,15,100),(2,25,200)");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      openSerializableRange(reader, result);
      execute(writer, new SqlExecutionResult(),
          "INSERT INTO entries VALUES (3,30,300)");
      assertWaitsThenSucceeds(reader, writer, executor,
          "INSERT INTO entries VALUES (4,12,400)", result);

      openSerializableRange(reader, result);
      assertWaitsThenSucceeds(reader, writer, executor,
          "UPDATE entries SET amount=101 WHERE id=1", result);

      openSerializableRange(reader, result);
      assertWaitsThenSucceeds(reader, writer, executor,
          "UPDATE entries SET bucket=25 WHERE id=1", result);

      openSerializableRange(reader, result);
      assertWaitsThenSucceeds(reader, writer, executor,
          "UPDATE entries SET bucket=15 WHERE id=2", result);

      openSerializableRange(reader, result);
      assertWaitsThenSucceeds(reader, writer, executor,
          "DELETE FROM entries WHERE id=2", result);
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, reader.close());
    assertEquals(StatusCode.OK, writer.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void openSerializableRange(
      SqlSession reader, SqlExecutionResult result) {
    assertEquals(StatusCode.OK, reader.execute("BEGIN SERIALIZABLE", result));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, reader.beginScan(
        "SELECT id FROM entries WHERE bucket>=10 AND bucket<20", cursor));
    while (reader.nextScan(cursor, row) == StatusCode.OK) { }
    assertEquals(StatusCode.OK, reader.closeScan(cursor, result));
  }

  private static void assertWaitsThenSucceeds(
      SqlSession reader,
      SqlSession writer,
      ExecutorService executor,
      String sql,
      SqlExecutionResult readerResult) throws Exception {
    AtomicReference<Thread> worker = new AtomicReference<>();
    Future<StatusCode> mutation = executor.submit(() -> {
      worker.set(Thread.currentThread());
      return writer.execute(sql, new SqlExecutionResult());
    });
    awaitParked(worker, mutation);
    assertFalse(mutation.isDone(), sql);
    assertEquals(StatusCode.OK, reader.execute("COMMIT", readerResult));
    assertEquals(StatusCode.OK, mutation.get(), sql);
  }

  private static void awaitParked(
      AtomicReference<Thread> worker, Future<StatusCode> mutation) {
    long deadline = System.nanoTime() + 1_000_000_000L;
    while (!mutation.isDone() && System.nanoTime() < deadline) {
      Thread thread = worker.get();
      if (thread != null && (thread.getState() == Thread.State.WAITING
          || thread.getState() == Thread.State.TIMED_WAITING)) return;
      Thread.onSpinWait();
    }
  }

  private static void createSchema(SqlSession session, SqlExecutionResult result) {
    execute(session, result, "CREATE TABLE customers (id BIGINT PRIMARY KEY,warehouse BIGINT,"
        + "district BIGINT,last_name BIGINT,first_name BIGINT)");
    execute(session, result, "CREATE INDEX customers_name ON customers"
        + "(warehouse,district,last_name,first_name,id)");
    execute(session, result, "CREATE TABLE orders (id BIGINT PRIMARY KEY,warehouse BIGINT,"
        + "district BIGINT,customer BIGINT,order_number BIGINT)");
    execute(session, result, "CREATE INDEX orders_latest ON orders"
        + "(warehouse,district,customer,order_number)");
    execute(session, result, "CREATE TABLE new_orders (id BIGINT PRIMARY KEY,warehouse BIGINT,"
        + "district BIGINT,order_number BIGINT)");
    execute(session, result, "CREATE INDEX new_orders_oldest ON new_orders"
        + "(warehouse,district,order_number)");
    execute(session, result, "CREATE TABLE order_lines (id BIGINT PRIMARY KEY,warehouse BIGINT,"
        + "district BIGINT,order_number BIGINT,line_number BIGINT)");
    execute(session, result, "CREATE INDEX order_lines_lookup ON order_lines"
        + "(warehouse,district,order_number,line_number)");
    execute(session, result, "CREATE TABLE stocks (id BIGINT PRIMARY KEY,warehouse BIGINT,"
        + "item BIGINT,quantity BIGINT)");
    execute(session, result, "CREATE INDEX stocks_probe ON stocks(warehouse,item)");
  }

  private static void insertRows(SqlSession session, SqlExecutionResult result) {
    execute(session, result, "INSERT INTO customers VALUES"
        + "(1,1,2,7,20),(2,1,2,7,10),(3,1,2,8,5)");
    execute(session, result, "INSERT INTO orders VALUES"
        + "(11,1,2,3,101),(12,1,2,3,102),(21,1,2,4,201)");
    execute(session, result, "INSERT INTO new_orders VALUES"
        + "(31,1,2,301),(32,1,2,302),(33,1,3,300)");
    execute(session, result, "INSERT INTO order_lines VALUES"
        + "(41,1,2,9,1),(42,1,2,9,2),(43,1,2,9,3),(44,1,2,9,4)");
    execute(session, result, "INSERT INTO stocks VALUES"
        + "(51,1,2,10),(52,1,3,20),(53,2,2,30)");
  }

  private static void assertIndexPlan(SqlSession session, String sql, boolean ordered) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN " + sql, cursor));
    boolean index = false;
    boolean table = false;
    boolean sort = false;
    while (session.nextScan(cursor, row) == StatusCode.OK) {
      index |= row.valueAt(0) == PackedText.pack("index");
      table |= row.valueAt(0) == PackedText.pack("table");
      sort |= row.valueAt(0) == PackedText.pack("sort");
    }
    assertTrue(index, sql);
    assertFalse(table, sql);
    if (ordered) assertFalse(sort, sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }

  private static void assertRows(SqlSession session, String sql, long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
      assertEquals(value, row.valueAt(0), sql);
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()), sql);
  }

  private static void assertSequentialRows(
      SqlSession session, String sql, int first, int last, int step) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
    for (int value = first; value != last + step; value += step) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
      assertEquals(value, row.valueAt(0), sql);
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()), sql);
  }

  private static void execute(SqlSession session, SqlExecutionResult result, String sql) {
    assertEquals(StatusCode.OK, session.execute(sql, result), sql);
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
