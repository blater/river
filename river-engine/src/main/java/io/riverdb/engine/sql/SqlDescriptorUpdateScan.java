package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Applies one descriptor update to every matched row through a reusable scan. */
final class SqlDescriptorUpdateScan {
  private final SqlDescriptorMutationValues values;
  private final SqlDescriptorColumnMapping columns;
  private final SqlDescriptorPredicate predicate;
  private final SqlDescriptorBoundPredicate boundPredicate;
  private final SqlRowProjectionEvaluator expressions;
  private final SqlDescriptorPointScanAccess access;
  private int affectedRows;

  SqlDescriptorUpdateScan(
      SqlDescriptorMutationValues mutationValues,
      SqlDescriptorColumnMapping columnMapping,
      SqlDescriptorPredicate rowPredicate,
      SqlDescriptorBoundPredicate expressionPredicate,
      SqlRowProjectionEvaluator expressionEvaluator,
      SqlDescriptorPointScanAccess scanAccess) {
    values = mutationValues;
    columns = columnMapping;
    predicate = rowPredicate;
    boundPredicate = expressionPredicate;
    expressions = expressionEvaluator;
    access = scanAccess;
  }

  StatusCode execute(SqlCommand command, SchemaPin pin) {
    affectedRows = 0;
    TableDescriptor table = pin.descriptor();
    StatusCode status = prepare(command, pin, table);
    if (status.isOk()) status = scan(command, table);
    StatusCode closed = access.close();
    return status.isOk() ? closed : status;
  }

  private StatusCode prepare(SqlCommand command, SchemaPin pin, TableDescriptor table) {
    StatusCode status = values.reserve(table);
    if (!status.isOk()) return status;
    status = columns.mapUpdate(command, table);
    if (!status.isOk()) return status;
    status = preparePredicate(command, table);
    if (!status.isOk()) return status;
    status = access.prepare(command, table, predicate);
    if (!status.isOk()) return status;
    return access.open(pin);
  }

  private StatusCode scan(SqlCommand command, TableDescriptor table) {
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      status = access.next(values);
      if (status == StatusCode.CONFLICT) { status = StatusCode.OK; break; }
      if (status.isOk()) status = evaluate();
      if (status.isOk() && matched()) status = lockAndUpdate(command, table);
    }
    return status;
  }

  int affectedRows() { return affectedRows; }

  private StatusCode preparePredicate(SqlCommand command, TableDescriptor table) {
    return boundPredicate.active()
        ? predicate.prepareIndexCandidates(command, table)
        : predicate.prepare(command, table);
  }

  private StatusCode evaluate() {
    return boundPredicate.active()
        ? boundPredicate.evaluate(values.fetched()) : predicate.evaluate(values.fetched());
  }

  private boolean matched() {
    return boundPredicate.active() ? boundPredicate.matched() : predicate.matched();
  }

  private StatusCode update(SqlCommand command, TableDescriptor table) {
    StatusCode status = values.buildUpdate(command, table, columns, expressions);
    if (status.isOk()) status = access.update(values);
    if (status.isOk()) affectedRows++;
    return status;
  }

  private StatusCode lockAndUpdate(SqlCommand command, TableDescriptor table) {
    StatusCode status = access.lockCandidate(values);
    if (status.isOk() && !access.candidateLocked()) return StatusCode.OK;
    if (status.isOk()) status = evaluate();
    if (status.isOk() && matched()) status = update(command, table);
    return access.finishCandidate(status);
  }
}
