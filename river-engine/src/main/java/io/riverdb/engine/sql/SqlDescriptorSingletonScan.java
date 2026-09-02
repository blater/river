package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Scans a non-proven point predicate without publishing a multi-row result. */
final class SqlDescriptorSingletonScan {
  private final RelationalSession session;
  private final SqlDescriptorMutationValues values;
  private final SqlDescriptorProjection projection;
  private final SqlDescriptorPredicate predicate;
  private final SqlDescriptorBoundPredicate boundPredicate;
  private final SqlDescriptorPointScanAccess access;
  private final SqlDescriptorSingletonPreparation preparation;
  private int matches;

  SqlDescriptorSingletonScan(
      RelationalSession relationalSession,
      SqlDescriptorMutationValues mutationValues,
      SqlDescriptorProjection resultProjection,
      SqlDescriptorPredicate rowPredicate,
      SqlDescriptorBoundPredicate expressionPredicate,
      SqlDescriptorPointScanAccess scanAccess) {
    session = relationalSession;
    values = mutationValues;
    projection = resultProjection;
    predicate = rowPredicate;
    boundPredicate = expressionPredicate;
    access = scanAccess;
    preparation = new SqlDescriptorSingletonPreparation(
        mutationValues, resultProjection, rowPredicate, expressionPredicate, scanAccess);
  }

  StatusCode execute(SqlCommand command, SchemaPin pin, SqlExecutionResult result) {
    matches = 0;
    TableDescriptor table = pin.descriptor();
    StatusCode status = preparation.open(command, pin, table);
    while (status.isOk()) {
      status = access.next(values);
      if (status == StatusCode.CONFLICT) { status = StatusCode.OK; break; }
      if (status.isOk()) status = evaluate();
      if (status.isOk() && matched()) status = publish(command, table, result);
      if (status.isOk() && matches != 0 && access.exactUnique()) break;
    }
    StatusCode closed = access.close();
    if (status.isOk()) status = closed;
    return status.isOk() && matches == 0 ? StatusCode.CONFLICT : status;
  }

  private StatusCode evaluate() {
    return boundPredicate.active()
        ? boundPredicate.evaluate(values.fetched()) : predicate.evaluate(values.fetched());
  }

  private boolean matched() {
    return boundPredicate.active() ? boundPredicate.matched() : predicate.matched();
  }

  private StatusCode publish(
      SqlCommand command, TableDescriptor table, SqlExecutionResult result) {
    if (matches != 0) {
      result.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = command.isSelectForUpdate()
        ? access.lockCandidate(values) : StatusCode.OK;
    if (status.isOk() && command.isSelectForUpdate() && !access.candidateLocked()) {
      return StatusCode.OK;
    }
    if (status.isOk() && command.isSelectForUpdate()) status = evaluate();
    if (status.isOk() && command.isSelectForUpdate() && !matched()) {
      return access.finishCandidate(StatusCode.OK);
    }
    if (status.isOk()) status = projection.publish(
        values.fetched(), SqlDescriptorPublicRowKey.from(table, values.fetched()),
        session.visibleCommitSequence(), result);
    if (command.isSelectForUpdate() && session.descriptorRows().currentBorrowed()) {
      if (status.isOk()) status = session.descriptorRows().retainCurrent();
      else {
        status = access.finishCandidate(status);
      }
    }
    if (status.isOk()) matches = 1;
    return status;
  }
}
