package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Statement-owned resources and bindings for one descriptor subquery edge. */
final class SqlDescriptorSubqueryFrameState {
  final RelationalSession session;
  final SchemaPin pin = new SchemaPin();
  final StatusDetail detail = new StatusDetail(128);
  final RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
  final RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
  final SqlDescriptorSubqueryRowValues values;
  final SqlDescriptorValueSource childSource = new SqlDescriptorValueSource();
  final SqlDescriptorCorrelatedPredicate predicate;
  final SqlDescriptorSubqueryIndexAccess index;
  final SqlDescriptorSubqueryProjection projection = new SqlDescriptorSubqueryProjection();
  final SqlDescriptorSubqueryOutcome outcome = new SqlDescriptorSubqueryOutcome();
  final SqlSubqueryPlan plan;
  final SqlSubqueryResultCache cache;
  final SqlPredicateOperand leftOperand = new SqlPredicateOperand();
  final SqlPredicateOperand candidateOperand = new SqlPredicateOperand();
  final int edge;
  SqlCommand command;
  int kind;
  int leftDescriptor;
  int childDescriptor;
  boolean caching;

  SqlDescriptorSubqueryFrameState(
      RelationalSession relationalSession, SqlSessionShapeBudget budget,
      SqlSubqueryPlan subqueryPlan, SqlSubqueryResultCache resultCache, int edgeIndex) {
    session = relationalSession;
    plan = subqueryPlan;
    cache = resultCache;
    edge = edgeIndex;
    values = new SqlDescriptorSubqueryRowValues(budget);
    predicate = new SqlDescriptorCorrelatedPredicate(budget);
    index = new SqlDescriptorSubqueryIndexAccess(budget);
  }

  boolean hasResources() { return cursor.isActive() || pin.isActive(); }

  StatusCode finishScan() {
    StatusCode status = cursor.isActive()
        ? session.descriptorRows().closeScan(cursor)
        : pin.isActive() ? pin.release() : StatusCode.OK;
    if (status.isOk()) status = cursor.reset();
    return status;
  }

  StatusCode close() {
    StatusCode status = cursor.isActive()
        ? session.descriptorRows().closeScan(cursor)
        : pin.isActive() ? pin.release() : StatusCode.OK;
    if (!cursor.isActive()) {
      StatusCode reset = cursor.reset();
      if (status.isOk()) status = reset;
    }
    if (status.isOk()) {
      command = null;
      predicate.reset();
      index.reset();
      values.reset();
      leftOperand.clear();
      candidateOperand.clear();
      kind = 0;
      leftDescriptor = 0;
      childDescriptor = 0;
      caching = false;
    }
    return status;
  }
}
