package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;

/** Owns the physical cursor and validates rows before the first block boundary. */
final class SqlBlockSource {
  private final io.riverdb.engine.relational.RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlRowProjectionEvaluator projections;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlBlockPhysicalRowReader physical = new SqlBlockPhysicalRowReader();
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult result = new RelationalScanResult();
  private final SqlJoinChainSource join;
  private final SqlSubqueryGraphExecution subqueries;
  private final SqlDescriptorSubqueryRowFrame descriptor;
  private boolean descriptorActive;
  private boolean graphDescriptor;

  SqlBlockSource(
      io.riverdb.engine.relational.RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlJoinChainSource joinSource,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlSubqueryGraphExecution graph,
      SqlRowProjectionEvaluator projectionEvaluator) {
    session = relationalSession;
    bound = statement;
    join = joinSource;
    subqueries = graph;
    predicates = predicateEvaluator;
    projections = projectionEvaluator;
    descriptor = new SqlDescriptorSubqueryRowFrame(relationalSession);
  }

  StatusCode begin(SqlBlockRowStore input, SqlBlockRow row) {
    if (input != null) return StatusCode.OK;
    if (bound.blockPlans().descriptorSource()) {
      int block = bound.blockPlans().count() - 1;
      graphDescriptor = bound.executableQuery.edgeCount() > 0;
      StatusCode status = graphDescriptor
          ? subqueries.beginBlockSource(block)
          : descriptor.prepare(bound.blockPlans().command(block).tableName());
      if (status.isOk() && !graphDescriptor) {
        SqlUniversalDescriptorIndexAccess root = bound.blockPlans().rootAccess();
        if (root.active()) descriptor.configureRoot(root);
        else descriptor.configureRoot(
            bound.blockPlans().command(block), bound.whereBoolean);
      }
      if (status.isOk() && !graphDescriptor) status = descriptor.begin();
      descriptorActive = status.isOk();
      return status;
    }
    StatusCode status = physical.prepare(bound.table, row);
    return status.isOk() ? session.beginScan(bound.table, cursor) : status;
  }

  StatusCode next(SqlBlockRowStore input, SqlBlockRow row) {
    if (input != null) return input.next(row);
    if (descriptorActive) {
      int block = bound.blockPlans().count() - 1;
      StatusCode status = graphDescriptor
          ? subqueries.nextBlockSource(block, row) : descriptor.next();
      if (!status.isOk() || graphDescriptor) return status;
      status = row.copyFrom(descriptor.row());
      if (status.isOk()) row.setKey(descriptor.publicKey());
      return status;
    }
    while (true) {
      StatusCode status = session.nextScan(cursor, result);
      if (!status.isOk()) return status;
      if (bound.executableQuery.edgeCount() == 0) {
        return physical.read(result.key(), result.row(), bound.table, row);
      }
      status = predicates.evaluate(result.key(), result.row());
      if (!status.isOk()) return status;
      if (!predicates.matched()) {
        predicates.releaseEvaluatedRow();
        continue;
      }
      status = physical.read(
          result.key(), predicates.evaluatedRow(result.row()), bound.table, row);
      predicates.releaseEvaluatedRow();
      return status;
    }
  }

  StatusCode finish(SqlBlockRowStore input, StatusCode status) {
    if (subqueries.hasResources() && !(descriptorActive && graphDescriptor)) {
      return status.isOk() ? StatusCode.CONFLICT : status;
    }
    StatusCode closed = input == null
        ? descriptorActive
            ? finishDescriptor(status)
            : cursor.isActive() ? session.closeScan(cursor) : StatusCode.OK
        : input.close();
    return status.isOk() ? closed : status;
  }

  StatusCode beginJoin() {
    return join.begin();
  }

  StatusCode configureJoin(
      SqlBoundJoinContext context,
      io.riverdb.sql.SqlCommand command,
      SqlBoundBooleanPredicateProgram where,
      SqlJoinPredicateCallback predicates) {
    return join.configure(
        context,
        command,
        where,
        predicates == null ? this.predicates : predicates);
  }

  void resetJoinMetrics() { join.resetMetrics(); }

  void publishJoinMetrics(SqlUniversalJoinSource source) {
    join.copyMetrics(source);
  }

  int accessColumn() {
    return graphDescriptor ? -1 : descriptor.accessColumn();
  }

  StatusCode nextJoin(SqlBlockRow row) {
    if (row == null) return StatusCode.CONFLICT;
    StatusCode status = join.next();
    if (status.isOk()) {
      status = projections.projectJoin(
          join.rows(),
          row);
    }
    if (!status.isOk() && status != StatusCode.CONFLICT) row.reset(0);
    return status;
  }

  StatusCode finishJoin(StatusCode status) {
    StatusCode closed = join.close();
    return status.isOk() ? closed : status;
  }

  boolean hasResources() {
    return descriptorActive || cursor.isActive() || join.hasResources();
  }

  StatusCode close() {
    StatusCode status = cursor.isActive() ? session.closeScan(cursor) : StatusCode.OK;
    if (status.isOk() && descriptorActive) status = finishDescriptor(StatusCode.OK);
    if (status.isOk() && subqueries.hasResources()) return StatusCode.CONFLICT;
    if (status.isOk()) status = descriptor.reset();
    if (status.isOk()) status = cursor.reset();
    if (status.isOk()) status = join.close();
    physical.reset();
    return status;
  }

  private StatusCode finishDescriptor(StatusCode body) {
    int block = bound.blockPlans().count() - 1;
    StatusCode status = graphDescriptor
        ? subqueries.finishBlockSource(block, body) : descriptor.closeScan();
    if (status.isOk()) {
      descriptorActive = false;
      graphDescriptor = false;
    }
    return body.isOk() ? status : body;
  }
}
