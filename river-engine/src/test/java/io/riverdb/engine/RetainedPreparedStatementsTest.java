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
  void sharesOnePlanAndReleasesItAfterIndependentHandlesClose(@TempDir Path root)
      throws Exception {
    TrackingBudget budget = new TrackingBudget(Long.MAX_VALUE);
    SessionHandleDirectory directory = new SessionHandleDirectory(budget);
    RetainedPreparedStatements statements = new RetainedPreparedStatements(
        budget, directory);
    try (Fixture fixture = openFixture(root, 0)) {
      String sql = "UPDATE t SET v=? WHERE id=?";
      PreparedOpenResult first = new PreparedOpenResult();
      PreparedOpenResult second = new PreparedOpenResult();
      SqlPreparedValidationResult validation = new SqlPreparedValidationResult();
      assertEquals(StatusCode.OK, statements.prepare(
          sql, fixture.session, validation, first));
      SqlPreparedPlan plan = statements.resolve(first.handle(), false);
      assertTrue(plan != null);
      assertEquals(StatusCode.OK, validation.reset());
      long retainedAfterFirst = budget.retained;

      validation = new SqlPreparedValidationResult();
      assertEquals(StatusCode.OK, statements.prepare(
          sql, fixture.session, validation, second));
      assertEquals(plan, statements.resolve(second.handle(), false));
      assertEquals(retainedAfterFirst, budget.retained);
      assertEquals(StatusCode.OK, validation.reset());

      assertTrue(statements.retain(first.handle()) != null);
      assertEquals(StatusCode.OK, statements.close(second.handle()));
      assertEquals(plan, statements.resolve(first.handle(), false));
      assertEquals(StatusCode.CONFLICT, statements.close(first.handle()));
      assertEquals(StatusCode.OK, statements.releaseReference(first.handle()));
      assertEquals(StatusCode.OK, statements.close(first.handle()));
      assertTrue(budget.retained > 0);

      PreparedOpenResult reopened = new PreparedOpenResult();
      validation = new SqlPreparedValidationResult();
      assertEquals(StatusCode.OK, statements.prepare(
          sql, fixture.session, validation, reopened));
      assertTrue(statements.resolve(reopened.handle(), false) != plan);
      assertEquals(StatusCode.OK, validation.reset());
      assertEquals(StatusCode.OK, statements.clear());
      assertEquals(StatusCode.OK, directory.clear());
      assertEquals(0, budget.retained);
    }
  }

  @Test
  void boundsOwnershipAndRejectsClosedGeneration(@TempDir Path root) throws Exception {
    TrackingBudget budget = new TrackingBudget(Long.MAX_VALUE);
    SessionHandleDirectory directory = new SessionHandleDirectory(budget);
    RetainedPreparedStatements statements = new RetainedPreparedStatements(
        budget, directory);
    try (Fixture fixture = openFixture(root, 1)) {
      String sql = "UPDATE t SET v=? WHERE id=?";
      PreparedOpenResult opened = new PreparedOpenResult();
      SqlPreparedValidationResult validation = new SqlPreparedValidationResult();
      assertEquals(StatusCode.OK, statements.prepare(sql, fixture.session, validation, opened));
      long first = opened.handle();
      assertTrue(first > 0);
      SqlPreparedPlan plan = statements.resolve(first, false);
      assertTrue(plan != null);
      assertNull(statements.resolve(first, true));
      assertEquals(StatusCode.OK, statements.close(first));
      assertNull(statements.resolve(first, false));
      validation = new SqlPreparedValidationResult();
      assertEquals(StatusCode.OK, statements.prepare(sql, fixture.session, validation, opened));
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
        String sql = "SELECT id AS c" + index + " FROM t";
        SqlPreparedValidationResult validation = new SqlPreparedValidationResult();
        assertEquals(StatusCode.OK, statements.prepare(sql, fixture.session, validation, opened));
        handles[index] = opened.handle();
      }
      long highWater = budget.retained;
      for (long handle : handles) assertEquals(StatusCode.OK, statements.close(handle));
      for (int index = 0; index < handles.length; index++) {
        String sql = "SELECT id AS c" + index + " FROM t";
        SqlPreparedValidationResult validation = new SqlPreparedValidationResult();
        assertEquals(StatusCode.OK, statements.prepare(sql, fixture.session, validation, opened));
      }
      assertEquals(highWater, budget.retained);
      assertEquals(StatusCode.OK, statements.clear());
      assertEquals(StatusCode.OK, directory.clear());
      assertEquals(0, budget.retained);
    }
  }

  @Test
  void rejectedKeyReservationLeavesExistingHandleAndBudgetIntact(@TempDir Path root)
      throws Exception {
    TrackingBudget budget = new TrackingBudget(Long.MAX_VALUE);
    SessionHandleDirectory directory = new SessionHandleDirectory(budget);
    RetainedPreparedStatements statements = new RetainedPreparedStatements(budget, directory);
    try (Fixture fixture = openFixture(root, 5)) {
      PreparedOpenResult opened = new PreparedOpenResult();
      SqlPreparedValidationResult validation = new SqlPreparedValidationResult();
      assertEquals(StatusCode.OK,
          statements.prepare("SELECT id FROM t", fixture.session, validation, opened));
      long originalHandle = opened.handle();
      SqlPreparedPlan original = statements.resolve(originalHandle, true);
      String secondSql = "SELECT v FROM t";
      SqlPreparedValidationResult measured = fixture.validation(secondSql, true, budget);
      long planBytes = measured.plan().byteCharge();
      assertEquals(StatusCode.OK, measured.reset());
      long retained = budget.retained;
      // Admit the compiled plan, then reject the additional retained key/entry storage.
      budget.maximum = retained + planBytes;
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          statements.prepare(secondSql, fixture.session, validation, opened));
      assertEquals(0, opened.handle());
      assertEquals(retained, budget.retained);
      assertEquals(original, statements.resolve(originalHandle, true));
      budget.maximum = Long.MAX_VALUE;
      assertEquals(StatusCode.OK,
          statements.prepare(secondSql, fixture.session, validation, opened));
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
          fixture.session.validatePrepared("SELECT * FROM t", null, budget, validation));
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
          "SELECT id AS " + "a".repeat(64) + " FROM t", null, limited, rejected));
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
      assertEquals(StatusCode.OK, session.validatePrepared(sql, null, budget, validation));
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
    private long maximum;
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
