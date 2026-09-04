package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public SQL evidence that materialized row ordinals are not narrowed to 16 bits. */
final class SqlLargeOrdinalPublicExecutionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4c415247454f5244L, 0x494e414c53514c31L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int ROWS = 65_537;
  private static final int INSERT_ROWS = 64;
  private static final int TRANSACTION_ROWS = 1_024;

  @Test
  void ordersAndFallbackJoinsBeyond65535AcrossCheckpointAndReopen(
      @TempDir Path root) throws IOException {
    Path spill = root.resolve("spill");
    Files.createDirectory(spill);
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.page=8KB\n"
            + "river.sql.materialized.cache=1MB\n"
            + "river.sql.materialized.sort-run=16KB\n"
            + "river.sql.join.hash-build-rows=1024\n"
            + "river.sql.join.hash-buckets=2048\n"
            + "river.sql.materialized.spill-directory=spill\n",
        StandardCharsets.UTF_8);

    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE ordinal_rows "
                + "(id BIGINT PRIMARY KEY,sort_key BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE ordinal_probes "
                + "(id BIGINT PRIMARY KEY,target BIGINT)",
            result));
    insertRows(session, result);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO ordinal_probes VALUES (1,0),(2,0),(3,1)", result));

    assertOrderedRows(session, result);
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertFalse(hasEntryNamed(spill, "query-"));
    assertEquals(StatusCode.OK, database.close());
    assertFalse(hasEntryNamed(spill, "open-"));

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    session = openSession(database);
    assertFallbackRowsBeyondBoundary(session, result);
    assertFallbackPlan(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertFalse(hasEntryNamed(spill, "query-"));
    assertEquals(StatusCode.OK, database.close());
    assertFalse(hasEntryNamed(spill, "open-"));
  }

  private static void insertRows(SqlSession session, SqlExecutionResult result) {
    for (int first = 0; first < ROWS; first += INSERT_ROWS) {
      if (first % TRANSACTION_ROWS == 0) {
        assertEquals(StatusCode.OK, session.execute("BEGIN", result));
      }
      int end = Math.min(ROWS, first + INSERT_ROWS);
      StringBuilder insert = new StringBuilder("INSERT INTO ordinal_rows VALUES ");
      for (int id = first; id < end; id++) {
        if (id > first) insert.append(',');
        insert.append('(').append(id).append(',').append(ROWS - 1L - id).append(')');
      }
      assertEquals(
          StatusCode.OK,
          session.execute(insert.toString(), result),
          "insert first=" + first);
      if (end % TRANSACTION_ROWS == 0 || end == ROWS) {
        assertEquals(
            StatusCode.OK,
            session.execute("COMMIT", result),
            "commit end=" + end);
      }
    }
  }

  private static void assertOrderedRows(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id,sort_key FROM ordinal_rows ORDER BY sort_key", cursor));
    long ordinal = 0;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      assertEquals(ROWS - 1L - ordinal, row.valueAt(0));
      assertEquals(ordinal, row.valueAt(1));
      ordinal++;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(ROWS, ordinal);
    assertEquals(ROWS, cursor.rowsReturned());
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertFallbackRowsBeyondBoundary(
      SqlSession session, SqlExecutionResult result) {
    String query =
        "SELECT b.id,p.id FROM ordinal_probes p "
            + "JOIN ordinal_rows b ON p.target=b.sort_key ORDER BY p.id";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(query, cursor));
    assertRow(session, cursor, row, 65_536, 1);
    assertRow(session, cursor, row, 65_536, 2);
    assertRow(session, cursor, row, 65_535, 3);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertFallbackPlan(
      SqlSession session, SqlExecutionResult result) {
    String query =
        "EXPLAIN ANALYZE SELECT p.id,b.id FROM ordinal_probes p "
            + "JOIN ordinal_rows b ON p.target=b.sort_key ORDER BY p.id";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(query, cursor));
    boolean fallback = false;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      fallback |= row.valueAt(0) == PackedText.pack("fallbk");
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertTrue(fallback);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertRow(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      long first,
      long second) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(first, row.valueAt(0));
    assertEquals(second, row.valueAt(1));
  }

  private static SqlSession openSession(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }

  private static boolean hasEntryNamed(Path root, String prefix) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.anyMatch(path -> {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(prefix);
      });
    }
  }
}
