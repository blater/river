package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorInsertBatchTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4453434241544348L, 0x5445535430303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void narrowerTextShapeReusesSessionAfterWideStatementAndBatch(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(" ".repeat(20_000) + "SELECT 1", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE accounts (id BIGINT PRIMARY KEY,balance BIGINT,region BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO accounts VALUES (1,100,7),(2,200,7),(3,300,8)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE labels (id BIGINT PRIMARY KEY,name VARCHAR(7) NOT NULL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO labels VALUES (1,'beta'),(2,'alpha')", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void admitsWholeBatchBeforeOneConsecutiveIdentityRange(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE batched (id BIGINT PRIMARY KEY,value BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(insertRows(1, 64), result));
    assertEquals(64, result.affectedRows());
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO batched VALUES (100,1),(100,2)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO batched VALUES (200,1)", result));
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO batched VALUES (201,1),(200,2)", result));
    assertEquals(StatusCode.CONFLICT,
        session.execute("SELECT id FROM batched WHERE id=201", result));
    assertEquals(StatusCode.OK, session.close());

    assertConsecutiveIdentities(database, 65);
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertConsecutiveIdentities(
      RelationalDatabase database, int expectedRows) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    RelationalSession session = opened.session();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.READ_COMMITTED));
    SchemaPin pin = new SchemaPin();
    assertEquals(StatusCode.OK,
        session.resolveDescriptor("batched", pin, new StatusDetail(128)));
    RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
    assertEquals(StatusCode.OK, session.descriptorRows().beginScan(pin, cursor));
    SqlValueBuffer values = new SqlValueBuffer();
    RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
    long prior = 0;
    int rows = 0;
    StatusCode status;
    while ((status = session.descriptorRows().nextScan(cursor, values, identity)).isOk()) {
      if (prior != 0) assertEquals(prior + 1, identity.logicalRowId());
      prior = identity.logicalRowId();
      rows++;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(expectedRows, rows);
    assertEquals(StatusCode.OK, session.descriptorRows().closeScan(cursor));
    assertEquals(StatusCode.OK, session.abort(new TransactionOutcome()));
  }

  private static String insertRows(int first, int count) {
    StringBuilder sql = new StringBuilder("INSERT INTO batched VALUES ");
    for (int row = 0; row < count; row++) {
      if (row != 0) sql.append(',');
      int key = first + row;
      sql.append('(').append(key).append(',').append(key * 10).append(')');
    }
    return sql.toString();
  }
}
