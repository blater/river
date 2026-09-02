package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.storage.heap.HeapRowResult;

/** Rechecks one snapshot candidate against its lock-protected current successor. */
final class SqlCurrentRowProtection {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBoundPredicateEvaluator predicates;
  private final HeapRowResult current = new HeapRowResult();
  private boolean borrowed;

  SqlCurrentRowProtection(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlBoundPredicateEvaluator predicateEvaluator) {
    session = relationalSession;
    bound = statement;
    predicates = predicateEvaluator;
  }

  StatusCode lockAndRecheck(long primaryKey) {
    if (borrowed) return StatusCode.CONFLICT;
    predicates.releaseEvaluatedRow();
    current.reset();
    StatusCode status = session.lockCurrentRow(bound.table, primaryKey, current);
    if (!status.isOk()) return status;
    borrowed = true;
    status = validCurrent();
    if (status.isOk()) status = predicates.evaluate(primaryKey, current);
    if (status.isOk() && !predicates.matched()) status = StatusCode.CONFLICT;
    if (status.isOk()) return status;
    predicates.releaseEvaluatedRow();
    return release(status);
  }

  HeapRowResult row() {
    return current;
  }

  StatusCode finish(StatusCode operation) {
    if (!borrowed) return StatusCode.INVALID_EXTERNAL_INPUT;
    predicates.releaseEvaluatedRow();
    StatusCode ownership = operation.isOk()
        ? retain() : session.releaseCurrentRow();
    borrowed = false;
    current.reset();
    return operation.isOk() || !ownership.isOk() ? ownership : operation;
  }

  private StatusCode retain() {
    StatusCode status = session.retainCurrentRow();
    if (status.isOk()) return status;
    StatusCode released = session.releaseCurrentRow();
    return released.isOk() ? status : released;
  }

  private StatusCode release(StatusCode operation) {
    StatusCode released = session.releaseCurrentRow();
    borrowed = false;
    current.reset();
    return released.isOk() ? operation : released;
  }

  private StatusCode validCurrent() {
    return current.length() >= bound.table.fixedRowBytes()
            && current.length() <= bound.table.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
