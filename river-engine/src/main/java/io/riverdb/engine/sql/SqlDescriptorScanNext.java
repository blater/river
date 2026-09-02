package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Publishes one direct, materialized, set, or aggregate descriptor result row. */
final class SqlDescriptorScanNext {
  private final SqlDescriptorScanContext context;

  SqlDescriptorScanNext(SqlDescriptorScanContext owner) { context = owner; }

  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    if (!context.active) return StatusCode.CONFLICT;
    if (context.scalarAggregate) return context.scalar.next(cursor, result);
    if (context.sets.active()) return context.sets.next(cursor, result, context.ordered);
    if (context.materialized) {
      return SqlDescriptorMaterializedPublisher.next(context, cursor, result);
    }
    return stream(cursor, result);
  }

  private StatusCode stream(SqlScanCursor cursor, SqlScanRowResult result) {
    while (!cursor.limitReached()) {
      StatusCode status = context.session.descriptorRows().nextScan(
          context.cursor, context.values.fetched(), context.identity);
      if (!status.isOk()) return status;
      status = context.evaluatePredicate(context.values.fetched());
      if (!status.isOk()) return status;
      if (!context.predicateMatched()) continue;
      if (context.forUpdate) {
        status = context.session.descriptorRows().lockScannedCandidate(
            context.cursor, context.values.fetched(), context.lockedCandidate);
        if (!status.isOk()) return status;
        if (!context.lockedCandidate.isLocked()) {
          continue;
        }
        status = context.evaluatePredicate(context.values.fetched());
        if (!status.isOk()) return release(status);
        if (!context.predicateMatched()) {
          status = release(StatusCode.OK);
          if (!status.isOk()) return status;
          continue;
        }
      }
      if (context.subqueries.active()) context.subqueries.parentAccepted();
      status = context.projection.publishScan(
          context.values.fetched(),
          SqlDescriptorPublicRowKey.from(
              context.cursor.descriptor(), context.values.fetched()), result);
      if (context.forUpdate) {
        if (status.isOk()) status = context.session.descriptorRows().retainCurrent();
        else status = release(status);
      }
      if (status.isOk()) cursor.rowReturned();
      return status;
    }
    return StatusCode.CONFLICT;
  }

  private StatusCode release(StatusCode original) {
    StatusCode released = context.session.descriptorRows().releaseCurrent();
    return released.isOk() ? original : released;
  }
}
