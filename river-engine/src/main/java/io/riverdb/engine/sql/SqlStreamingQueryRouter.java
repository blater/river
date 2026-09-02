package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;

/** Routes one parsed streaming query to its concrete binding path. */
final class SqlStreamingQueryRouter {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBinder binder;
  private final SqlViewExpander views;
  private final SqlQueryExecution queries;
  private final SqlStreamingQueryBinder bindings;

  SqlStreamingQueryRouter(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlBinder sqlBinder,
      SqlViewExpander viewExpander,
      SqlQueryExecution queryExecution,
      SqlStreamingQueryBinder queryBindings) {
    session = relationalSession;
    bound = statement;
    binder = sqlBinder;
    views = viewExpander;
    queries = queryExecution;
    bindings = queryBindings;
  }

  StatusCode prepare() {
    SqlCommandType type = bound.command.type();
    if (bound.query.hasSetExpression()) return queries.prepareUnion();
    if (catalog(type)) return binder.captureExecutableQuery(bound);
    if (bound.query.isBlockPipeline()) return bindings.blockPipeline();
    StatusCode expansion = views.resolve(session, bound);
    if (expansion.isOk()) expansion = binder.captureExecutableQuery(bound);
    if (terminal(expansion)) return expansion;
    if (expansion.isOk() && bound.query.isBlockPipeline()) {
      return bindings.blockPipeline();
    }
    type = bound.command.type();
    StatusCode routed = routeDescriptor(type);
    if (routed != StatusCode.CONFLICT) return routed;
    if (!expansion.isOk()) return expansion;
    if (type == SqlCommandType.JOIN_SCAN) return bindings.join();
    if (SqlBinder.isGroupAggregate(type)) return bindings.group();
    if (type == SqlCommandType.DISTINCT_SCAN) return bindings.distinct();
    return bindings.data(type);
  }

  private StatusCode routeDescriptor(SqlCommandType type) {
    if (SqlDescriptorQueryTypes.handlesUniversalJoin(type)) {
      StatusCode status = queries.resolveUniversalJoin(bound.joinContext(0));
      if (!status.isOk()) return status;
      if (queries.universalJoinMatched()) {
        if (bound.query.edgeCount() == 0 && !bound.command.isOrdered()) {
          return bindings.universalJoin();
        }
        status = queries.releaseUniversalJoin();
        return status.isOk() ? bindings.promoteBlockPipeline() : status;
      }
    }
    if (!SqlDescriptorQueryTypes.handles(type)) return StatusCode.CONFLICT;
    StatusCode status = queries.prepareDescriptorScan();
    if (status == StatusCode.FEATURE_NOT_SUPPORTED) {
      return bindings.promoteBlockPipeline();
    }
    return !status.isOk() || queries.descriptorScanMatched()
        ? status : StatusCode.CONFLICT;
  }

  private static boolean catalog(SqlCommandType type) {
    return type == SqlCommandType.SHOW_TABLES
        || type == SqlCommandType.SHOW_INDEXES
        || type == SqlCommandType.SHOW_COLUMNS;
  }

  private static boolean terminal(StatusCode status) {
    return !status.isOk()
        && status != StatusCode.CONFLICT
        && status != StatusCode.CORRUPTION;
  }
}
