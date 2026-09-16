package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Owns a point scalar aggregate scan over descriptor-backed rows. */
final class SqlDescriptorAggregateExecution {
  private final RelationalSession session;
  private final RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
  private final RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
  private final SqlDescriptorMutationValues values = new SqlDescriptorMutationValues();
  private final SqlDescriptorPredicate predicate = new SqlDescriptorPredicate();
  private final SqlDescriptorScalarAggregate aggregate;

  SqlDescriptorAggregateExecution(
      RelationalSession relationalSession,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    aggregate = new SqlDescriptorScalarAggregate(temporal, shapeBudget);
  }

  StatusCode execute(SqlCommand command, SchemaPin pin, SqlExecutionResult result) {
    TableDescriptor table = pin.descriptor();
    StatusCode status = prepare(command, pin, table);
    if (status.isOk()) status = scanRows();
    if (status == StatusCode.CONFLICT) status = StatusCode.OK;
    StatusCode closed = closeCursor();
    if (status.isOk()) status = closed;
    if (status.isOk()) status = aggregate.finish();
    if (status.isOk()) status = aggregate.publish(result, session.visibleCommitSequence());
    if (status.isOk()) aggregate.reset();
    return status;
  }

  private StatusCode prepare(
      SqlCommand command, SchemaPin pin, TableDescriptor table) {
    StatusCode status = values.reserve(table);
    if (!status.isOk()) return status;
    status = predicate.prepare(command, table);
    if (!status.isOk()) return status;
    status = aggregate.prepare(command, table, null);
    if (!status.isOk()) return status;
    status = cursor.reset();
    if (!status.isOk()) return status;
    return session.descriptorRows().beginScan(pin, cursor);
  }

  private StatusCode scanRows() {
    while (true) {
      StatusCode status = session.descriptorRows().nextScan(
          cursor, values.fetched(), identity);
      if (status == StatusCode.CONFLICT) return status;
      if (!status.isOk()) return status;
      status = predicate.evaluate(values.fetched());
      if (!status.isOk()) return status;
      if (predicate.matched()) {
        status = aggregate.accumulate(values.fetched());
        if (!status.isOk()) return status;
      }
    }
  }

  boolean hasResources() { return cursor.isActive(); }

  StatusCode close() {
    StatusCode status = closeCursor();
    if (status.isOk()) aggregate.reset();
    return status;
  }

  private StatusCode closeCursor() {
    StatusCode status = cursor.isActive()
        ? session.descriptorRows().closeScan(cursor) : StatusCode.OK;
    return status.isOk() ? cursor.reset() : status;
  }
}
