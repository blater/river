package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSessionLeaseTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(8_201, 8_209);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void directDatabaseCloseWaitsForSqlSession(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();

    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    assertEquals(StatusCode.CONFLICT, database.close());
    assertEquals(StatusCode.OK, sessionResult.session().close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void sessionCreationAfterDatabaseCloseReturnsClosed(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();

    assertEquals(StatusCode.OK, opened.database().close());
    assertEquals(
        StatusCode.CLOSED,
        SqlSession.create(opened.database(), sessionResult));
    assertNull(sessionResult.session());
  }

  @Test
  void sessionCloseCleansActiveScanAndReleasesLeaseOnce(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    SqlScanCursor cursor = new SqlScanCursor();

    assertEquals(StatusCode.OK, session.execute("CREATE TABLE close_scan", execution));
    assertEquals(StatusCode.OK, session.beginScan("SELECT key, value FROM close_scan", cursor));
    assertEquals(StatusCode.OK, session.close());
    assertFalse(cursor.isActive());
    assertEquals(StatusCode.CLOSED, session.close());
    assertEquals(StatusCode.OK, database.close());
  }
}
