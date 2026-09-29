package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Opens the selected descriptor source and performs eager consumers when required. */
final class SqlDescriptorScanOpen {
  private final SqlDescriptorScanContext context;

  SqlDescriptorScanOpen(SqlDescriptorScanContext owner) { context = owner; }

  StatusCode open() {
    if (!context.matched || !context.pin.isActive()) return StatusCode.CONFLICT;
    StatusCode status = context.index.active()
        ? context.session.descriptorRows().beginIndexScan(
            context.pin, context.index.bounds(),
            context.index.serializableSourceMode(context.forUpdate),
            context.cursor)
        : context.session.descriptorRows().beginScan(context.pin, context.cursor);
    if (status.isOk() && context.scalarAggregate) status = aggregateRows();
    else if (status.isOk() && context.materialized) {
      status = SqlDescriptorScanMaterializer.materialize(
          context.session, context.cursor, context.view, context.identity,
          context.predicate, context.boundPredicate, context.subqueries, context.ordered);
    }
    if (status.isOk()) context.active = true;
    return status;
  }

  private StatusCode aggregateRows() {
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      status = context.session.descriptorRows().nextScan(
          context.cursor, context.view, context.identity, null, null);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = context.evaluatePredicate(context.view);
      if (status.isOk() && context.predicateMatched()) {
        status = context.scalar.accumulate(context.view);
      }
    }
    StatusCode closed = context.cursor.isActive()
        ? context.session.descriptorRows().closeScan(context.cursor) : StatusCode.OK;
    if (status.isOk()) status = closed;
    if (status.isOk()) status = context.cursor.reset();
    return status.isOk() ? context.scalar.finish() : status;
  }
}
