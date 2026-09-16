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
    if (status.isOk()) status = scanCandidates(command, table, result);
    StatusCode closed = access.close();
    if (status.isOk()) status = closed;
    return status.isOk() && matches == 0 ? StatusCode.CONFLICT : status;
  }

  private StatusCode scanCandidates(
      SqlCommand command, TableDescriptor table, SqlExecutionResult result) {
    while (true) {
      StatusCode status = access.next(values);
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      status = evaluate();
      if (status.isOk() && matched()) status = publish(command, table, result);
      if (!status.isOk()) return status;
      if (matches != 0 && access.exactUnique()) return StatusCode.OK;
    }
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
    boolean forUpdate = command.isSelectForUpdate();
    StatusCode status = forUpdate
        ? access.lockCandidate(values) : StatusCode.OK;
    if (forUpdate && status.isOk()) {
      if (!access.candidateLocked()) return StatusCode.OK;
      status = evaluate();
      if (status.isOk() && !matched()) return access.finishCandidate(StatusCode.OK);
    }
    if (!status.isOk()) return finishCandidate(status, forUpdate);
    status = projection.publish(
        values.fetched(), SqlDescriptorPublicRowKey.from(table, values.fetched()),
        session.visibleCommitSequence(), result);
    status = finishCandidate(status, forUpdate);
    if (status.isOk()) matches = 1;
    return status;
  }

  private StatusCode finishCandidate(StatusCode status, boolean forUpdate) {
    if (!forUpdate || !session.descriptorRows().currentBorrowed()) return status;
    return status.isOk()
        ? session.descriptorRows().retainCurrent() : access.finishCandidate(status);
  }
}
