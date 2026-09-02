package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;

/** Resolves one point source before choosing descriptor or block execution. */
final class SqlPointDescriptorViewPreparation {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlViewExpander views;
  private final SqlDescriptorPointExecution descriptor;

  SqlPointDescriptorViewPreparation(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlViewExpander viewExpander,
      SqlDescriptorPointExecution descriptorExecution) {
    session = relationalSession;
    bound = statement;
    views = viewExpander;
    descriptor = descriptorExecution;
  }

  StatusCode prepare() {
    StatusCode status = descriptor.prepare(bound.command);
    if (status != StatusCode.CONFLICT) return status;
    StatusCode expanded = views.resolve(session, bound);
    if (!expanded.isOk()) return expanded;
    return bound.expandedView && !bound.query.isBlockPipeline()
            && !bound.query.hasNestedTopology()
        ? descriptor.prepare(bound.command) : status;
  }
}
