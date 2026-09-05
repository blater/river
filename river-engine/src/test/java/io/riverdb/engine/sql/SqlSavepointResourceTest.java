package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.tx.api.IsolationLevel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSavepointResourceTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(9_401, 9_409);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void admittedHighWaterSupportsMoreThanThreeAndReusesClearedSlots(@TempDir Path root) {
    Fixture fixture = open(root);

    assertEquals(
        StatusCode.OK,
        fixture.transactions.beginExplicit(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("first"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("second"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("third"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("fourth"));
    long retained = fixture.budget.retainedBytes();
    assertTrue(retained > 0);

    assertEquals(StatusCode.OK, fixture.transactions.releaseUserSavepoint("third"));
    assertEquals(StatusCode.CONFLICT, fixture.transactions.rollbackToUserSavepoint("fourth"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("third"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("fourth"));
    assertEquals(retained, fixture.budget.retainedBytes());

    assertEquals(StatusCode.OK, fixture.transactions.rollbackToUserSavepoint("second"));
    assertEquals(StatusCode.CONFLICT, fixture.transactions.releaseUserSavepoint("third"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("third"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("fourth"));
    assertEquals(retained, fixture.budget.retainedBytes());
    assertEquals(StatusCode.OK, fixture.transactions.commitExplicit());

    assertEquals(
        StatusCode.OK,
        fixture.transactions.beginExplicit(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("first"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("second"));
    assertEquals(retained, fixture.budget.retainedBytes());
    assertEquals(StatusCode.OK, fixture.transactions.abortExplicit());
    assertEquals(StatusCode.OK, fixture.session.close());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  @Test
  void exhaustionPrecedesSavepointMutationAndPreservesReusableState(@TempDir Path root) {
    Fixture fixture = open(root);

    assertEquals(
        StatusCode.OK,
        fixture.transactions.beginExplicit(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("first"));
    assertEquals(StatusCode.OK, fixture.transactions.abortExplicit());
    long firstHighWater = fixture.budget.retainedBytes();
    long filler = Long.MAX_VALUE - firstHighWater;
    assertEquals(StatusCode.OK, fixture.budget.reserve(filler));

    assertEquals(
        StatusCode.OK,
        fixture.transactions.beginExplicit(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("first"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("second"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("third"));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("fourth"));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        fixture.transactions.createUserSavepoint("fifth"));
    assertEquals(Long.MAX_VALUE, fixture.budget.retainedBytes());

    assertEquals(StatusCode.OK, fixture.budget.release(filler));
    assertEquals(StatusCode.OK, fixture.transactions.createUserSavepoint("fifth"));
    assertEquals(StatusCode.OK, fixture.transactions.rollbackToUserSavepoint("fifth"));
    assertEquals(StatusCode.OK, fixture.transactions.abortExplicit());
    assertEquals(StatusCode.OK, fixture.session.close());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  @Test
  void sqlSessionCloseAbortsSavepointsAndReturnsRuntimeLease(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT first", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT second", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT third", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT fourth", result));
    assertTrue(session.retainedShapeBytes() > 0);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void retainedGrowthCalculationRejectsOverflow() {
    assertEquals(1_152, SqlTransactionState.retainedUserSavepointGrowthBytes(0, 4));
    assertEquals(-1, SqlTransactionState.retainedUserSavepointGrowthBytes(4, 4));
    assertEquals(-1, SqlTransactionState.retainedUserSavepointGrowthBytes(-1, 4));
    assertEquals(
        -1,
        SqlTransactionState.retainedUserSavepointGrowthBytes(0, Long.MAX_VALUE));
  }

  @Test
  void retainedChargeCoversGeometricLowerStackStatementSlack() {
    final long conservativeArrayHeaderBytes = 32;
    final long nonCompressedReferenceBytes = 8;
    for (int userCapacity = 4; userCapacity <= 1 << 30; userCapacity *= 2) {
      int required = userCapacity + 1;
      int lowerCapacity = BoundedArrayGrowth.capacity(
          userCapacity, required, Integer.MAX_VALUE, 4);
      long lowerRetained = conservativeArrayHeaderBytes
          + lowerCapacity * nonCompressedReferenceBytes;
      assertTrue(
          SqlTransactionState.retainedLowerSavepointStackCoverageBytes(userCapacity)
              >= lowerRetained,
          "userCapacity=" + userCapacity + ", lowerCapacity=" + lowerCapacity);
      if (userCapacity == 1 << 30) break;
    }
  }

  private static Fixture open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession session = sessionResult.session();
    SqlSessionShapeBudget budget = new SqlSessionShapeBudget(null);
    return new Fixture(
        database, session, budget, new SqlTransactionState(session, budget));
  }

  private record Fixture(
      RelationalDatabase database,
      RelationalSession session,
      SqlSessionShapeBudget budget,
      SqlTransactionState transactions) {}
}
