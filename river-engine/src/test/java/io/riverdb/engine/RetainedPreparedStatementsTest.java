package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.sql.SqlPreparedPlan;
import io.riverdb.engine.sql.SqlPreparedValidationResult;
import io.riverdb.engine.sql.SqlRetainedBudget;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlSession;
import io.riverdb.engine.sql.SqlSessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RetainedPreparedStatementsTest {
  @Test
  void boundsOwnershipAndRejectsClosedGeneration(@TempDir Path root) throws Exception {
    TrackingBudget budget = new TrackingBudget(Long.MAX_VALUE);
    SessionHandleDirectory directory = new SessionHandleDirectory(budget);
    RetainedPreparedStatements statements = new RetainedPreparedStatements(
        budget, directory);
    try (Fixture fixture = openFixture(root, 1)) {
      SqlPreparedValidationResult validation = fixture.validation(
          "UPDATE t SET v=? WHERE id=?", false, budget);
      SqlPreparedPlan plan = validation.plan();
      PreparedOpenResult opened = new PreparedOpenResult();
      assertEquals(StatusCode.OK, statements.open(validation, opened));
      assertEquals(StatusCode.OK, validation.reset());
      assertNull(validation.plan());
      long first = opened.handle();
      assertTrue(first > 0);
      assertEquals(plan, statements.resolve(first, false));
      assertNull(statements.resolve(first, true));
      assertEquals(StatusCode.OK, statements.close(first));
      assertNull(statements.resolve(first, false));
      validation = fixture.validation("UPDATE t SET v=? WHERE id=?", false, budget);
      assertEquals(StatusCode.OK, statements.open(validation, opened));
      assertEquals(StatusCode.OK, validation.reset());
      assertNull(statements.resolve(first, false));
      assertEquals(StatusCode.OK, statements.clear());
      assertEquals(StatusCode.OK, directory.clear());
      assertEquals(0, budget.retained);
    }
  }

  @Test
  void growsPastFormerCountLimitAndReusesAccountedChunks(@TempDir Path root) throws Exception {
    TrackingBudget budget = new TrackingBudget(Long.MAX_VALUE);
    SessionHandleDirectory directory = new SessionHandleDirectory(budget);
    RetainedPreparedStatements statements = new RetainedPreparedStatements(
        budget, directory);
    try (Fixture fixture = openFixture(root, 2)) {
      PreparedOpenResult opened = new PreparedOpenResult();
      long[] handles = new long[PreparedStatementChunk.SLOT_COUNT * 3];
      for (int index = 0; index < handles.length; index++) {
        SqlPreparedValidationResult validation = fixture.validation(
            "SELECT * FROM t", true, budget);
        assertEquals(StatusCode.OK, statements.open(validation, opened));
        assertEquals(StatusCode.OK, validation.reset());
        handles[index] = opened.handle();
      }
      long highWater = budget.retained;
      for (long handle : handles) assertEquals(StatusCode.OK, statements.close(handle));
      for (int index = 0; index < handles.length; index++) {
        SqlPreparedValidationResult validation = fixture.validation(
            "SELECT * FROM t", true, budget);
        assertEquals(StatusCode.OK, statements.open(validation, opened));
        assertEquals(StatusCode.OK, validation.reset());
      }
      assertEquals(highWater, budget.retained);
      assertEquals(StatusCode.OK, statements.clear());
      assertEquals(StatusCode.OK, directory.clear());
      assertEquals(0, budget.retained);
    }
  }

  @Test
  void databaseBudgetPressureRejectsBeforeFreezingAPlan(@TempDir Path root) throws Exception {
    TrackingBudget budget = new TrackingBudget(PreparedStatementChunk.ACCOUNTED_BYTES);
    try (Fixture fixture = openFixture(root, 3)) {
      SqlPreparedValidationResult validation = new SqlPreparedValidationResult();
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          fixture.session.validatePrepared("SELECT * FROM t", budget, validation));
      assertNull(validation.plan());
      assertEquals(0, budget.retained);
    }
  }

  @Test
  void chargesActualIdentifierPayloadAndReleasesRejectedOwnership(@TempDir Path root)
      throws Exception {
    try (Fixture fixture = openFixture(root, 4)) {
      TrackingBudget measured = new TrackingBudget(Long.MAX_VALUE);
      SqlPreparedValidationResult shortName = fixture.validation(
          "SELECT id AS x FROM t", true, measured);
      long shortBytes = shortName.plan().byteCharge();
      assertEquals(StatusCode.OK, shortName.reset());
      SqlPreparedValidationResult longName = fixture.validation(
          "SELECT id AS " + "a".repeat(64) + " FROM t", true, measured);
      assertTrue(longName.plan().byteCharge() > shortBytes);
      assertEquals(StatusCode.OK, longName.reset());
      assertEquals(0, measured.retained);

      TrackingBudget limited = new TrackingBudget(shortBytes);
      SqlPreparedValidationResult rejected = new SqlPreparedValidationResult();
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, fixture.session.validatePrepared(
          "SELECT id AS " + "a".repeat(64) + " FROM t", limited, rejected));
      assertNull(rejected.plan());
      assertEquals(0, limited.retained);
    }
  }

  private static Fixture openFixture(Path root, long incarnation) {
    RelationalDatabaseOpenResult databaseResult = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(4),
        root, DatabaseIncarnation.of(
            0x5052455041524544L, 0x53544F5245544553L + incarnation),
        WalGeneration.of(1), 4, databaseResult));
    RelationalDatabase database = databaseResult.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY,v INTEGER)", execution));
    return new Fixture(database, session);
  }

  private static final class Fixture implements AutoCloseable {
    private final RelationalDatabase database;
    private final SqlSession session;

    private Fixture(RelationalDatabase relationalDatabase, SqlSession sqlSession) {
      database = relationalDatabase;
      session = sqlSession;
    }

    private SqlPreparedValidationResult validation(
        String sql, boolean queryStatement, SqlRetainedBudget budget) {
      SqlPreparedValidationResult validation = new SqlPreparedValidationResult();
      assertEquals(StatusCode.OK, session.validatePrepared(sql, budget, validation));
      assertEquals(queryStatement, validation.query());
      return validation;
    }

    @Override
    public void close() {
      assertEquals(StatusCode.OK, session.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }

  private static final class TrackingBudget implements SqlRetainedBudget {
    private final long maximum;
    private long retained;

    private TrackingBudget(long maximumBytes) { maximum = maximumBytes; }

    @Override
    public StatusCode reserveRetainedBytes(long bytes) {
      if (bytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (bytes > maximum - retained) return StatusCode.RESOURCE_EXHAUSTED;
      retained += bytes;
      return StatusCode.OK;
    }

    @Override
    public StatusCode releaseRetainedBytes(long bytes) {
      if (bytes <= 0 || bytes > retained) return StatusCode.INVARIANT_BROKEN;
      retained -= bytes;
      return StatusCode.OK;
    }
  }
}
