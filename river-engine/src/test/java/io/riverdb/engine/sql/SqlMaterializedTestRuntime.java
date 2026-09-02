package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.engine.runtime.SqlDatabaseRuntime;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import java.nio.file.Path;

/** Test-owned runtime lease for statement materialization. */
final class SqlMaterializedTestRuntime {
  private final SqlDatabaseRuntime runtime;
  private final SqlRuntimeLease lease;
  private final SqlSessionShapeBudget budget;

  private SqlMaterializedTestRuntime(
      SqlDatabaseRuntime retainedRuntime, SqlRuntimeLease retainedLease) {
    runtime = retainedRuntime;
    lease = retainedLease;
    budget = new SqlSessionShapeBudget(lease);
  }

  static SqlMaterializedTestRuntime open(Path root) {
    StatusDetail detail = new StatusDetail(256);
    RiverRuntimeConfig.Result config = new RiverRuntimeConfig.Result();
    assertEquals(StatusCode.OK, RiverRuntimeConfig.load(
        root, 64_000_000L, root.toString(), config, detail));
    SqlDatabaseRuntime.OpenResult opened = new SqlDatabaseRuntime.OpenResult();
    assertEquals(StatusCode.OK, SqlDatabaseRuntime.create(
        config.config(), root, DatabaseIncarnation.of(810, 811), opened, detail));
    SqlRuntimeLeaseResult acquired = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, opened.runtime().acquire(acquired));
    return new SqlMaterializedTestRuntime(opened.runtime(), acquired.lease());
  }

  SqlSortWorkspace workspace() {
    return new SqlSortWorkspace(SqlRetainedArrayAllocator.STANDARD, budget);
  }

  void close() {
    StatusDetail detail = new StatusDetail(256);
    assertEquals(StatusCode.OK, budget.closeMaterialized(detail));
    assertEquals(StatusCode.OK, lease.close());
    assertEquals(StatusCode.OK, runtime.prepareClose());
    assertEquals(StatusCode.OK, runtime.completeClose());
  }
}
