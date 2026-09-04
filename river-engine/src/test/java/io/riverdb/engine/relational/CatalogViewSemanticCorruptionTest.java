package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlScanCursor;
import io.riverdb.engine.sql.SqlSession;
import io.riverdb.engine.sql.SqlSessionOpenResult;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CatalogViewSemanticCorruptionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x56494557434f5252L, 0x555054494f4e3031L);
  private static final DatabaseIncarnation CORRUPT_LINEAGE_DATABASE =
      DatabaseIncarnation.of(0x56494557434f5252L, 0x555054494f4e3032L);

  @Test
  void mapsChecksumValidUnusableViewProgramToCorruption(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession sql = openSql(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        sql.execute(
            "CREATE TABLE moments (id BIGINT PRIMARY KEY, day DATE)", result));
    assertEquals(
        StatusCode.OK,
        sql.execute(
            "CREATE TABLE facts (id BIGINT PRIMARY KEY, day DATE)", result));
    assertEquals(
        StatusCode.OK,
        sql.execute(
            "CREATE VIEW damaged_view AS SELECT id FROM moments", result));
    for (String name : new String[] {
        "swapped_view", "duplicate_view", "missing_view", "invalid_zone_view"
    }) {
      assertEquals(
          StatusCode.OK,
          sql.execute(
              "CREATE VIEW " + name + " AS SELECT id FROM moments", result));
    }
    assertEquals(StatusCode.OK, sql.close());

    RelationalSessionOpenResult relationalResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(relationalResult));
    RelationalSession relational = relationalResult.session();
    assertEquals(StatusCode.OK, relational.begin(IsolationLevel.SERIALIZABLE));
    int moments = descriptorTableId(relational, "moments");
    int facts = descriptorTableId(relational, "facts");
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    assertEquals(
        StatusCode.OK,
        encode(
            encoded,
            "damaged_view",
            "SELECT moments.id AS bad FROM moments JOIN moments m "
                + "ON moments.id=m.id WHERE EXTRACT(DAY FROM moments.day)=29",
            moments,
            0));
    RelationalKey.KeyResult key = new RelationalKey.KeyResult();
    assertEquals(
        StatusCode.OK, RelationalKey.catalogTableKey("damaged_view", key));
    assertEquals(
        StatusCode.OK,
        relational.indexedSession().update(key.space(), key.key(), encoded));
    assertEquals(
        StatusCode.OK,
        encode(
            encoded,
            "duplicate_view",
            "SELECT m.id AS bad FROM moments m JOIN facts f ON m.id=f.id",
            moments,
            moments));
    assertEquals(
        StatusCode.OK, RelationalKey.catalogTableKey("duplicate_view", key));
    assertEquals(
        StatusCode.OK,
        relational.indexedSession().update(key.space(), key.key(), encoded));
    assertEquals(
        StatusCode.OK,
        encode(
            encoded,
            "swapped_view",
            "SELECT m.id AS bad FROM moments m JOIN facts f ON m.id=f.id",
            facts,
            moments));
    assertEquals(
        StatusCode.OK, RelationalKey.catalogTableKey("swapped_view", key));
    assertEquals(
        StatusCode.OK,
        relational.indexedSession().update(key.space(), key.key(), encoded));
    assertEquals(
        StatusCode.OK,
        encode(
            encoded,
            "missing_view",
            "SELECT m.id AS bad FROM moments m JOIN facts f ON m.id=f.id",
            moments,
            RelationalKey.MAXIMUM_TABLE_ID));
    assertEquals(
        StatusCode.OK, RelationalKey.catalogTableKey("missing_view", key));
    assertEquals(
        StatusCode.OK,
        relational.indexedSession().update(key.space(), key.key(), encoded));
    assertEquals(
        StatusCode.OK,
        encode(
            encoded,
            "invalid_zone_view",
            "SELECT m.id AS bad FROM moments m JOIN facts f "
                + "ON m.id=f.id AND "
                + "(m.day AT TIME ZONE 'No/Such') IS NOT NULL",
            moments,
            facts));
    assertEquals(
        StatusCode.OK, RelationalKey.catalogTableKey("invalid_zone_view", key));
    assertEquals(
        StatusCode.OK,
        relational.indexedSession().update(key.space(), key.key(), encoded));
    assertEquals(
        StatusCode.OK,
        encode(
            encoded,
            "malformed_aggregate",
            "SELECT SUM(id) AS total FROM moments",
            moments,
            0));
    int queryOffset = 24 + ViewDefinition.MAXIMUM_LINEAGE_TABLES * Integer.BYTES
        + "malformed_aggregate".length();
    encoded.put(queryOffset, (byte) 0xc0);
    encoded.put(queryOffset + 1, (byte) 0x80);
    assertEquals(
        StatusCode.OK,
        RelationalKey.catalogTableKey("malformed_aggregate", key));
    assertEquals(
        StatusCode.OK,
        relational.indexedSession().insert(key.space(), key.key(), encoded));
    assertEquals(
        StatusCode.OK, relational.commit(new TransactionOutcome()));

    sql = openSql(database);
    assertEquals(
        StatusCode.CORRUPTION,
        sql.execute(
            "SELECT bad FROM damaged_view WHERE bad=1",
            new SqlExecutionResult()));
    assertEquals(
        StatusCode.CORRUPTION,
        sql.execute("SELECT bad FROM swapped_view", new SqlExecutionResult()));
    assertEquals(
        StatusCode.CORRUPTION,
        sql.execute("SELECT bad FROM duplicate_view", new SqlExecutionResult()));
    assertEquals(
        StatusCode.CORRUPTION,
        sql.execute("SELECT bad FROM invalid_zone_view", new SqlExecutionResult()));
    assertEquals(
        StatusCode.CORRUPTION,
        sql.execute("SELECT bad FROM missing_view", new SqlExecutionResult()));
    assertEquals(
        StatusCode.OK,
        sql.execute("DROP VIEW missing_view", new SqlExecutionResult()));
    assertEquals(StatusCode.OK, sql.close());
    sql = openSql(database);
    assertEquals(
        StatusCode.CORRUPTION,
        sql.beginScan("SELECT total FROM malformed_aggregate", new SqlScanCursor()));
    assertEquals(StatusCode.OK, sql.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void corruptLineageFailsClosedDuringDependencyScan(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            databaseRequest(8),
            root, CORRUPT_LINEAGE_DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession sql = openSql(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        sql.execute(
            "CREATE TABLE events (id BIGINT PRIMARY KEY,value BIGINT)", result));
    assertEquals(
        StatusCode.OK,
        sql.execute("INSERT INTO events VALUES (1,10)", result));
    assertEquals(
        StatusCode.OK,
        sql.execute("CREATE VIEW corrupt_view AS SELECT id FROM events", result));
    assertEquals(StatusCode.OK, sql.close());

    RelationalSessionOpenResult relationalResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(relationalResult));
    RelationalSession relational = relationalResult.session();
    assertEquals(StatusCode.OK, relational.begin(IsolationLevel.SERIALIZABLE));
    int events = descriptorTableId(relational, "events");
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    assertEquals(
        StatusCode.OK,
        encode(
            encoded,
            "corrupt_view",
            "SELECT id FROM events",
            events,
            0));
    encoded.putInt(28, events);
    RelationalKey.KeyResult key = new RelationalKey.KeyResult();
    assertEquals(StatusCode.OK, RelationalKey.catalogTableKey("corrupt_view", key));
    assertEquals(
        StatusCode.OK,
        relational.indexedSession().update(key.space(), key.key(), encoded));
    assertEquals(StatusCode.OK, relational.commit(new TransactionOutcome()));

    sql = openSql(database);
    assertEquals(StatusCode.CORRUPTION, sql.execute("DROP TABLE events", result));
    assertEquals(StatusCode.OK, sql.execute("SELECT id FROM events WHERE id=1", result));
    assertEquals(1, result.valueAt(0));
    assertEquals(StatusCode.CORRUPTION, sql.execute("DROP VIEW corrupt_view", result));
    assertEquals(StatusCode.OK, sql.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static SqlSession openSql(RelationalDatabase database) {
    SqlSessionOpenResult result = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, result));
    return result.session();
  }

  private static StatusCode encode(
      ByteBuffer target,
      CharSequence name,
      CharSequence query,
      int firstTableId,
      int secondTableId) {
    int[] tableIds = secondTableId == 0
        ? new int[] {firstTableId} : new int[] {firstTableId, secondTableId};
    return CatalogViewCodec.encode(
        target, name, query, tableIds, tableIds.length);
  }

  private static int descriptorTableId(RelationalSession session, CharSequence name) {
    SchemaPin pin = new SchemaPin();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, session.resolveDescriptor(name, pin, detail));
    long tableId = pin.tableId();
    assertEquals(tableId, (long) (int) tableId);
    assertEquals(StatusCode.OK, pin.release());
    return (int) tableId;
  }
}
