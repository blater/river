package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Facade over one reusable descriptor index/table scan execution. */
final class SqlDescriptorScanExecution {
  private final SqlDescriptorScanContext context;
  private final SqlDescriptorScanPreparation preparation;
  private final SqlDescriptorScanOpen open;
  private final SqlDescriptorScanNext next;
  private final SqlDescriptorScanCleanup cleanup;

  SqlDescriptorScanExecution(
      RelationalSession session,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget,
      BoundSqlStatement bound,
      SqlBinder binder,
      SqlBoundPredicateEvaluator predicates) {
    context = new SqlDescriptorScanContext(session, temporal, shapeBudget, predicates);
    preparation = new SqlDescriptorScanPreparation(context, bound, binder);
    open = new SqlDescriptorScanOpen(context);
    next = new SqlDescriptorScanNext(context);
    cleanup = new SqlDescriptorScanCleanup(context);
  }

  StatusCode prepare(SqlCommand command, SqlQuery query, SqlPhysicalPlan plan) {
    StatusCode status = cleanup.close();
    if (status.isOk()) status = preparation.prepare(command, query, plan);
    if (!status.isOk()) cleanup.close();
    return status;
  }

  StatusCode open() { return open.open(); }
  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    return next.next(cursor, result);
  }
  boolean matched() { return context.matched; }
  boolean active() { return context.active; }
  boolean activeWith(SqlActiveScanState legacy) { return context.active || legacy.isActive(); }
  StatusCode close() { return cleanup.close(); }
}
