package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorIndexBounds;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.btree.TupleBTreeScanBounds;

/** Adds the optional next-key range and publishes complete tuple bounds. */
final class SqlDescriptorSubqueryRangeWriter {
  private boolean empty;

  StatusCode bind(
      SqlDescriptorSubqueryIndexPlan plan,
      SqlDescriptorPrimaryValues lower,
      SqlDescriptorPrimaryValues upper,
      SqlDescriptorValueSource outer,
      RelationalDescriptorIndexBounds bounds) {
    empty = false;
    int lowParts = plan.equalParts();
    int highParts = plan.equalParts();
    boolean lowRange = false;
    boolean highRange = false;
    StatusCode status = StatusCode.OK;
    if (plan.lowerComparison() != null) {
      status = assign(plan, plan.lower(), lower, outer);
      if (status.isOk() && !empty) { lowParts++; lowRange = true; }
      else if (status == StatusCode.CONFLICT) status = StatusCode.OK;
    }
    if (status.isOk() && !empty && plan.upperComparison() != null) {
      status = assign(plan, plan.upper(), upper, outer);
      if (status.isOk() && !empty) { highParts++; highRange = true; }
      else if (status == StatusCode.CONFLICT) status = StatusCode.OK;
    }
    return status.isOk() && !empty ? bounds.set(
        plan.key(), lowParts == 0 ? null : lower.buffer(), lowParts,
        !lowRange || inclusive(plan.lowerComparison()),
        highParts == 0 ? null : upper.buffer(), highParts,
        !highRange || inclusive(plan.upperComparison()),
        TupleBTreeScanBounds.FORWARD) : status;
  }

  private StatusCode assign(
      SqlDescriptorSubqueryIndexPlan plan,
      SqlDescriptorSubqueryIndexBinding binding,
      SqlDescriptorPrimaryValues target,
      SqlDescriptorValueSource outer) {
    if (binding.nullValue(outer)) {
      empty = true;
      return StatusCode.OK;
    }
    int part = plan.equalParts();
    return binding.assign(target, plan.key().columnOrdinalAt(part),
        plan.key().typeDescriptorAt(part), outer);
  }

  boolean empty() { return empty; }
  void reset() { empty = false; }

  private static boolean inclusive(SqlComparison comparison) {
    return comparison == null || comparison == SqlComparison.GREATER_OR_EQUAL
        || comparison == SqlComparison.LESS_OR_EQUAL;
  }
}
