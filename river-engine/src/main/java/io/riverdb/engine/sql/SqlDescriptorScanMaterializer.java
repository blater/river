package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;

/** Drains one physical descriptor scan into retained row storage. */
final class SqlDescriptorScanMaterializer {
  private SqlDescriptorScanMaterializer() { }

  static StatusCode materialize(
      RelationalSession session,
      RelationalDescriptorScanCursor cursor,
      SqlDescriptorMutationValues values,
      RelationalRowIdentityResult identity,
      SqlDescriptorPredicate predicate,
      SqlDescriptorBoundPredicate boundPredicate,
      SqlDescriptorSubqueryExecution subqueries,
      SqlDescriptorOrderedRows ordered) {
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      status = session.descriptorRows().nextScan(cursor, values.fetched(), identity);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = boundPredicate.active()
          ? boundPredicate.evaluate(values.fetched()) : predicate.evaluate(values.fetched());
      boolean matched = boundPredicate.active()
          ? boundPredicate.matched() : predicate.matched();
      if (status.isOk() && matched) {
        status = ordered.append(values.fetched(), identity.logicalRowId());
        if (status.isOk() && subqueries.active()) subqueries.parentAccepted();
      }
    }
    StatusCode closed = session.descriptorRows().closeScan(cursor);
    if (status.isOk()) status = closed;
    if (status.isOk()) status = cursor.reset();
    return status.isOk() ? ordered.finish() : status;
  }
}
