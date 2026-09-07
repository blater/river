package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSavepointBudgetBoundaryTest {
  @Test
  void namedGrowthHonorsRealLeaseAndPreservesStatementRollback(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(databaseRequest(6), root,
        DatabaseIncarnation.of(9_421, 9_429), WalGeneration.of(1), 6, opened));
    RelationalDatabase database = opened.database();
    RelationalSessionOpenResult relational = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(relational));
    SqlRuntimeLeaseResult runtime = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, database.services().acquireRuntime(runtime));
    SqlRuntimeLease lease = runtime.lease();
    SqlSession session = new SqlSession(
        new SqlSessionExecutionCoordinator(database, relational.session(), null, lease));
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE accounts", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO accounts VALUES (1, 100)", result));
    for (String name : new String[] {"first", "second", "third", "fourth"}) {
      assertEquals(StatusCode.OK, session.execute("SAVEPOINT " + name, result));
    }
    // Four named handles plus the statement handle exceed the first lower-stack allocation.
    assertEquals(StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO accounts VALUES (1, 101)", result));
    assertEquals(StatusCode.OK, session.execute("SELECT value FROM accounts WHERE key=1", result));
    assertEquals(100, result.value());
    long retained = session.retainedShapeBytes();
    assertEquals(retained, lease.reservedBytes());
    long filler = session.maximumShapeBytes() - retained;
    assertEquals(StatusCode.OK, session.reserveRetainedBytes(filler));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, session.execute("SAVEPOINT fifth", result));
    assertEquals(session.maximumShapeBytes(), lease.reservedBytes());
    assertEquals(StatusCode.OK, session.execute("ROLLBACK TO SAVEPOINT fourth", result));
    assertEquals(StatusCode.OK, session.releaseRetainedBytes(filler));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT fifth", result));
    assertEquals(StatusCode.OK, session.execute("SELECT value FROM accounts WHERE key=1", result));
    assertEquals(100, result.value());
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(session.retainedShapeBytes(), lease.reservedBytes());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(0, lease.reservedBytes());
    assertEquals(StatusCode.CLOSED, session.close());
    SqlRuntimeLeaseResult replacement = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, database.services().acquireRuntime(replacement));
    assertEquals(StatusCode.OK, replacement.lease().reserve(session.maximumShapeBytes()));
    assertEquals(StatusCode.OK, replacement.lease().close());
    assertEquals(StatusCode.OK, database.close());
  }
}
