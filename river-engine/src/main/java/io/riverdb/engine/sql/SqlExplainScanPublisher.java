package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Publishes one retained EXPLAIN step without adding scan-owner complexity. */
final class SqlExplainScanPublisher {
  private SqlExplainScanPublisher() {}

  static StatusCode next(
      SqlPhysicalPlan plan,
      SqlActiveScanState scan,
      long[] values,
      int[] descriptors,
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    int step = scan.currentPlanStep(plan.stepCount());
    if (step < 0) return StatusCode.CONFLICT;
    values[0] = plan.operator(step);
    values[1] = plan.detail(step);
    values[2] = plan.stepRows(step);
    long nullMask = plan.explainAnalyzed() && plan.stepRows(step) >= 0
        ? 0 : 1L << 2;
    result.set(step, values, nullMask, descriptors, 3);
    StatusCode status = result.setPackedTextAt(0, plan.operator(step));
    if (status.isOk()) {
      scan.advancePlanStep();
      cursor.rowReturned();
    }
    return status;
  }
}
