package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Writes the common equality prefix into both reusable tuple bounds. */
final class SqlDescriptorSubqueryEqualityWriter {
  private boolean empty;

  StatusCode bind(
      SqlDescriptorSubqueryIndexPlan plan,
      SqlDescriptorPrimaryValues lower,
      SqlDescriptorPrimaryValues upper,
      SqlDescriptorValueSource outer) {
    empty = false;
    for (int part = 0; part < plan.equalParts(); part++) {
      int column = plan.key().columnOrdinalAt(part);
      int descriptor = plan.key().typeDescriptorAt(part);
      StatusCode status = plan.equal(part).assign(lower, column, descriptor, outer);
      if (status.isOk()) {
        status = plan.equal(part).assign(upper, column, descriptor, outer);
      }
      if (status == StatusCode.CONFLICT) {
        empty = true;
        return StatusCode.OK;
      }
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  boolean empty() { return empty; }
  void reset() { empty = false; }
}
