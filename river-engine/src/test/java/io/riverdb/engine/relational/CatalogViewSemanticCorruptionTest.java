package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlScanCursor;
import io.riverdb.engine.sql.SqlSession;
import io.riverdb.engine.sql.SqlSessionOpenResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CatalogViewSemanticCorruptionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x56494557434f5252L, 0x555054494f4e3031L);

  @Test
  void mapsChecksumValidUnusableViewProgramToCorruption(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, WalGeneration.of(1), 8, opened));
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
            "CREATE VIEW damaged_view AS SELECT id FROM moments", result));
    assertEquals(StatusCode.OK, sql.close());

    RelationalSessionOpenResult relationalResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(relationalResult));
    RelationalSession relational = relationalResult.session();
    assertEquals(StatusCode.OK, relational.begin(IsolationLevel.SERIALIZABLE));
    TableDefinition moments = new TableDefinition();
    assertEquals(StatusCode.OK, relational.resolveTable("moments", moments));
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    assertEquals(
        StatusCode.OK,
        CatalogViewCodec.encode(
            encoded,
            "damaged_view",
            "SELECT moments.id AS bad FROM moments JOIN moments m "
                + "ON moments.id=m.id WHERE EXTRACT(DAY FROM moments.day)=29",
            moments.tableId()));
    RelationalKey.KeyResult key = new RelationalKey.KeyResult();
    assertEquals(
        StatusCode.OK, RelationalKey.catalogTableKey("damaged_view", key));
    assertEquals(
        StatusCode.OK,
        relational.indexedSession().update(key.space(), key.key(), encoded));
    assertEquals(
        StatusCode.OK,
        CatalogViewCodec.encode(
            encoded,
            "malformed_aggregate",
            "SELECT SUM(id) AS total FROM moments",
            moments.tableId()));
    int queryOffset = 24 + "malformed_aggregate".length();
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
    assertEquals(StatusCode.OK, sql.close());
    sql = openSql(database);
    assertEquals(
        StatusCode.CORRUPTION,
        sql.beginScan("SELECT total FROM malformed_aggregate", new SqlScanCursor()));
    assertEquals(StatusCode.OK, sql.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static SqlSession openSql(RelationalDatabase database) {
    SqlSessionOpenResult result = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, result));
    return result.session();
  }
}
