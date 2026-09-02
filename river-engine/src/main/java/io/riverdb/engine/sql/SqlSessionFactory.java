package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import io.riverdb.engine.runtime.SqlRuntimeLease;

/** Failure-translating construction boundary for a SQL session and its runtime lease. */
final class SqlSessionFactory {
  private SqlSessionFactory() { }

  static StatusCode create(
      RelationalDatabase database,
      SessionAuthorizer authorizer,
      SqlSessionOpenResult result) {
    SqlRuntimeLease acquired = null;
    RelationalSession acquiredRelational = null;
    try {
      RelationalSessionOpenResult relational = new RelationalSessionOpenResult();
      StatusCode status = database.createSession(relational);
      if (!status.isOk()) return status;
      acquiredRelational = relational.session();
      SqlRuntimeLeaseResult runtime = new SqlRuntimeLeaseResult();
      status = database.services().acquireRuntime(runtime);
      if (!status.isOk()) {
        acquiredRelational.close();
        return status;
      }
      acquired = runtime.lease();
      SqlSessionExecutionCoordinator coordinator =
          new SqlSessionExecutionCoordinator(
              database, acquiredRelational, authorizer, acquired);
      SqlSession session = new SqlSession(coordinator);
      result.set(session);
      return status;
    } catch (OutOfMemoryError error) {
      if (acquired != null) acquired.close();
      if (acquiredRelational != null) acquiredRelational.close();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
