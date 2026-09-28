package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlTrustedStoredValueTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x545255535456414cL, 0x5545533030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void numericUpdatesRetainTextThroughPendingWritesRollbackAndRestart(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    execute(session, result, "CREATE TABLE trusted_values ("
        + "id BIGINT PRIMARY KEY,quantity INTEGER,"
        + "label VARCHAR(8),note VARCHAR(8))");
    execute(session, result, "INSERT INTO trusted_values VALUES "
        + "(1,5,'多🙂',NULL),(2,7,'','£')");

    execute(session, result, "BEGIN");
    execute(session, result, "UPDATE trusted_values SET quantity=6 WHERE id=1");
    assertEquals(6, number(session, result, 1));
    assertEquals("多🙂", text(session, result, "label", 1));
    assertEquals(StatusCode.OK,
        session.execute("SELECT note FROM trusted_values WHERE id=1", result));
    assertEquals(true, result.isNull(0));
    execute(session, result, "ROLLBACK");
    assertEquals(5, number(session, result, 1));
    assertEquals("多🙂", text(session, result, "label", 1));

    assertEquals(StatusCode.DATATYPE_MISMATCH, session.execute(
        "UPDATE trusted_values SET label='123456789' WHERE id=1", result));
    assertEquals("多🙂", text(session, result, "label", 1));
    execute(session, result, "UPDATE trusted_values SET quantity=9 WHERE id=2");
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.openExisting(
        databaseRequest(8), root, DATABASE, GENERATION, 8,
        EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    session = session(database);
    assertEquals(9, number(session, result, 2));
    assertEquals("", text(session, result, "label", 2));
    assertEquals("£", text(session, result, "note", 2));
    assertEquals("多🙂", text(session, result, "label", 1));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void legacySqlUpdateTrustsUnchangedTextButRawRowAdmissionRejectsBadBytes(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession sql = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    execute(sql, result, "CREATE TABLE legacy_values ("
        + "id BIGINT PRIMARY KEY,quantity INTEGER,label VARCHAR(8),CHECK(1=1))");
    execute(sql, result, "INSERT INTO legacy_values VALUES (1,5,'多🙂')");
    execute(sql, result, "UPDATE legacy_values SET quantity=6 WHERE id=1");
    assertEquals(6, number(sql, result, 1, "legacy_values"));
    assertEquals("多🙂", text(sql, result, "label", 1, "legacy_values"));
    assertEquals(StatusCode.OK, sql.close());

    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession raw = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, raw.begin(IsolationLevel.SERIALIZABLE));
    TableDefinition table = new TableDefinition();
    assertEquals(StatusCode.OK, raw.resolveTable("legacy_values", table));
    ByteBuffer bad = ByteBuffer.allocateDirect(table.fixedRowBytes() + 2);
    bad.putLong(table.valueOffset(1), 7);
    bad.putLong(table.valueOffset(2), (long) table.fixedRowBytes() << 32 | 2);
    bad.put(table.fixedRowBytes(), (byte) 0xc0);
    bad.put(table.fixedRowBytes() + 1, (byte) 0xaf);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, raw.updateRow(table, 1, bad));
    assertEquals(StatusCode.OK, raw.abort(outcome));
    assertEquals(StatusCode.OK, raw.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void blockReadUsesTransitivePhysicalColumnDemand(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession sql = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    execute(sql, result, "CREATE TABLE block_values ("
        + "id BIGINT PRIMARY KEY,quantity INTEGER,label VARCHAR(8),CHECK(1=1))");
    execute(sql, result, "INSERT INTO block_values VALUES (1,5,'多🙂')");
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult selected = new SqlScanRowResult();
    assertEquals(StatusCode.OK, sql.beginScan(
        "SELECT quantity FROM "
            + "(SELECT quantity,label FROM block_values) block_source", cursor));
    assertEquals(StatusCode.OK, sql.nextScan(cursor, selected));
    assertEquals(5, selected.valueAt(0));
    assertEquals(StatusCode.CONFLICT, sql.nextScan(cursor, selected));
    assertEquals(StatusCode.OK, sql.closeScan(cursor, result));
    assertEquals(StatusCode.OK, sql.beginScan(
        "SELECT q FROM "
            + "(SELECT quantity AS q,label AS l FROM block_values) block_source", cursor));
    assertEquals(StatusCode.OK, sql.nextScan(cursor, selected));
    assertEquals(5, selected.valueAt(0));
    assertEquals(StatusCode.CONFLICT, sql.nextScan(cursor, selected));
    assertEquals(StatusCode.OK, sql.closeScan(cursor, result));
    assertEquals(StatusCode.OK, sql.beginScan(
        "SELECT label FROM (SELECT label FROM block_values) block_source", cursor));
    assertEquals(StatusCode.OK, sql.nextScan(cursor, selected));
    char[] selectedText = new char[8];
    int selectedLength = selected.copyTextAt(0, selectedText, 0);
    assertEquals("多🙂", new String(selectedText, 0, selectedLength));
    assertEquals(StatusCode.CONFLICT, sql.nextScan(cursor, selected));
    assertEquals(StatusCode.OK, sql.closeScan(cursor, result));
    assertEquals(StatusCode.OK, sql.close());

    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession raw = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, raw.begin(IsolationLevel.SERIALIZABLE));
    TableDefinition table = new TableDefinition();
    assertEquals(StatusCode.OK, raw.resolveTable("block_values", table));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, raw.fetch(table, 1, fetched));
    ByteBuffer bytes = ByteBuffer.allocate(fetched.length());
    assertEquals(StatusCode.OK, fetched.copyTo(bytes));
    bytes.flip();
    int textOffset = (int) (bytes.getLong(table.valueOffset(2)) >>> 32);
    bytes.put(textOffset, (byte) 0xc0);
    HeapRowResult damaged = new HeapRowResult();
    damaged.set(bytes, fetched.rowId(), 0, fetched.length());

    SqlBlockPhysicalRowDecoding decoder = new SqlBlockPhysicalRowDecoding();
    SqlBlockRow row = new SqlBlockRow();
    SqlBoundBlockPlans plans = blockPlans(raw,
        "SELECT quantity FROM (SELECT quantity,label FROM block_values) block_source");
    assertEquals(false, plans.physicalColumnLive(2));
    assertEquals(StatusCode.OK, decoder.prepare(table, row, plans));
    assertEquals(StatusCode.OK, decoder.read(1, damaged, table, row));
    assertEquals(5, row.value(1));
    plans = blockPlans(raw,
        "SELECT q FROM (SELECT quantity AS q,label AS l FROM block_values) block_source");
    assertEquals(false, plans.physicalColumnLive(2));
    assertEquals(StatusCode.OK, decoder.prepare(table, row, plans));
    assertEquals(StatusCode.OK, decoder.read(1, damaged, table, row));

    String[] requiredText = {
      "SELECT label FROM (SELECT label FROM block_values) block_source",
      "SELECT quantity FROM "
          + "(SELECT quantity,label FROM block_values) block_source WHERE label='多🙂'",
      "SELECT quantity FROM "
          + "(SELECT quantity FROM block_values WHERE label='多🙂') block_source",
      "SELECT quantity FROM "
          + "(SELECT quantity,label FROM block_values ORDER BY label) block_source",
      "SELECT q FROM "
          + "(SELECT quantity AS q,label AS l FROM block_values ORDER BY label) block_source",
      "SELECT n FROM "
          + "(SELECT label,COUNT(*) AS n FROM block_values GROUP BY label) block_source",
      "SELECT n FROM "
          + "(SELECT COUNT(label) AS n FROM block_values) block_source",
      "SELECT n FROM "
          + "(SELECT COUNT(*) AS n FROM block_values HAVING COUNT(label)>0) block_source",
      "SELECT quantity FROM "
          + "(SELECT DISTINCT quantity,label FROM block_values) block_source"
    };
    for (String query : requiredText) {
      plans = blockPlans(raw, query);
      assertEquals(true, plans.physicalColumnLive(2), query);
      assertEquals(StatusCode.OK, decoder.prepare(table, row, plans), query);
      assertEquals(StatusCode.CORRUPTION,
          decoder.read(1, damaged, table, row), query);
    }
    assertEquals(StatusCode.OK, raw.abort(outcome));
    assertEquals(StatusCode.OK, raw.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void selectAllRetainsAllPhysicalColumns() {
    SqlCommand selectAll = new SqlCommand();
    assertEquals(StatusCode.OK,
        new SqlParser().parse("SELECT * FROM block_values", selectAll));
    SqlBlockSchema base = new SqlBlockSchema();
    base.set(3);
    base.setColumn(0, "id", io.riverdb.base.type.SqlTypeDescriptor.BIGINT, false);
    base.setColumn(1, "quantity", io.riverdb.base.type.SqlTypeDescriptor.INTEGER, false);
    base.setColumn(2, "label", io.riverdb.base.type.SqlTypeDescriptor.varchar(8), false);
    SqlBlockSchema output = new SqlBlockSchema();
    output.copyFrom(base);
    SqlBlockProjectionLiveness liveness = new SqlBlockProjectionLiveness();
    liveness.prepare(new SqlCommand[] {selectAll}, new SqlBlockSchema[] {output}, base, 1);
    assertEquals(true, liveness.physicalLive(2));
  }

  @Test
  void aliasedInnerOrderColumnRemainsLive(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession sql = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    execute(sql, result, "CREATE TABLE block_values ("
        + "id BIGINT PRIMARY KEY,quantity INTEGER,label VARCHAR(8),CHECK(1=1))");
    execute(sql, result, "INSERT INTO block_values VALUES (1,5,'z'),(2,7,'a')");
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult selected = new SqlScanRowResult();
    assertEquals(StatusCode.OK, sql.beginScan(
        "SELECT q FROM (SELECT quantity AS q,label AS l "
            + "FROM block_values ORDER BY label LIMIT 1) s", cursor));
    assertEquals(StatusCode.OK, sql.nextScan(cursor, selected));
    assertEquals(7, selected.valueAt(0));
    assertEquals(StatusCode.CONFLICT, sql.nextScan(cursor, selected));
    assertEquals(StatusCode.OK, sql.closeScan(cursor, result));
    assertEquals(StatusCode.OK, sql.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static SqlBoundBlockPlans blockPlans(RelationalSession session, String sql) {
    BoundSqlStatement bound = new BoundSqlStatement();
    assertEquals(StatusCode.OK,
        new SqlParser().parseQuery(sql, bound.query, bound.command), sql);
    assertEquals(StatusCode.OK, bound.query.promoteRootBlockPipeline(bound.command), sql);
    assertEquals(StatusCode.OK, new SqlBlockPlanBinder(null).bind(session, bound, null), sql);
    return bound.blockPlans();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }

  private static void execute(
      SqlSession session, SqlExecutionResult result, String sql) {
    assertEquals(StatusCode.OK, session.execute(sql, result), sql);
  }

  private static long number(
      SqlSession session, SqlExecutionResult result, int id) {
    return number(session, result, id, "trusted_values");
  }

  private static long number(
      SqlSession session, SqlExecutionResult result, int id, String table) {
    execute(session, result,
        "SELECT quantity FROM " + table + " WHERE id=" + id);
    return result.valueAt(0);
  }

  private static String text(
      SqlSession session, SqlExecutionResult result, String column, int id) {
    return text(session, result, column, id, "trusted_values");
  }

  private static String text(
      SqlSession session, SqlExecutionResult result, String column, int id,
      String table) {
    execute(session, result,
        "SELECT " + column + " FROM " + table + " WHERE id=" + id);
    char[] characters = new char[16];
    int length = result.copyTextAt(0, characters, 0);
    return new String(characters, 0, length);
  }
}
