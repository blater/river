package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Deletes every descriptor row matched by one reusable scan. */
final class SqlDescriptorDeleteScan {
  private final SqlDescriptorMutationValues values;
  private final SqlDescriptorPredicate predicate;
  private final SqlDescriptorBoundPredicate boundPredicate;
  private final SqlDescriptorPointScanAccess access;
  private int affectedRows;

  SqlDescriptorDeleteScan(
      SqlDescriptorMutationValues mutationValues,
      SqlDescriptorPredicate rowPredicate,
      SqlDescriptorBoundPredicate expressionPredicate,
      SqlDescriptorPointScanAccess scanAccess) {
    values = mutationValues;
    predicate = rowPredicate;
    boundPredicate = expressionPredicate;
    access = scanAccess;
  }

  StatusCode execute(SqlCommand command, SchemaPin pin) {
    affectedRows = 0;
    TableDescriptor table = pin.descriptor();
    StatusCode status = values.reserve(table);
    if (status.isOk()) status = preparePredicate(command, table);
    if (status.isOk()) status = access.prepare(command, table, predicate);
    if (status.isOk()) status = access.open(pin);
    while (status.isOk()) {
      status = access.next(values);
      if (status == StatusCode.CONFLICT) { status = StatusCode.OK; break; }
      if (status.isOk()) status = evaluate();
      if (status.isOk() && matched()) {
        status = lockAndDelete();
      }
    }
    StatusCode closed = access.close();
    return status.isOk() ? closed : status;
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

  private StatusCode lockAndDelete() {
    StatusCode status = access.lockCandidate(values);
    if (status.isOk() && !access.candidateLocked()) return StatusCode.OK;
    if (status.isOk()) status = evaluate();
    if (status.isOk() && matched()) {
      status = access.delete();
      if (status.isOk()) affectedRows++;
    }
    return access.finishCandidate(status);
  }
}
