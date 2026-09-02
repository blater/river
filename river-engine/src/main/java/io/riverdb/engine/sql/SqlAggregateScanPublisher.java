package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Publishes one already-computed scalar aggregate scan row. */
final class SqlAggregateScanPublisher {
  private SqlAggregateScanPublisher() {}

  static StatusCode next(
      SqlPhysicalPlan plan,
      SqlActiveScanState scan,
      long[] values,
      int[] descriptors,
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    if (cursor.rowsReturned() > 0 || !scan.aggregateAvailable()) {
      return StatusCode.CONFLICT;
    }
    values[0] = scan.aggregateValue();
    StatusCode status = result.beginProjected(0, descriptors, 1);
    if (status.isOk()) {
      if (scan.aggregateNull()) result.setProjectedNull(0);
      else if (SqlTypeDescriptor.isWideDecimal(descriptors[0])) {
        result.setProjectedDecimal128(0, scan.aggregateHigh(), scan.aggregateValue());
      } else result.setProjectedValue(0, scan.aggregateValue());
    }
    if (status.isOk() && scan.aggregateTextLength() >= 0
        && plan.resultType(0) != 0
        && SqlTypeDescriptor.typeId(plan.resultType(0)) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && !scan.aggregateNull()) {
      status = result.setTextAt(
          0, scan.aggregateText(), 0, scan.aggregateTextLength());
    }
    if (status.isOk()) cursor.rowReturned();
    return status;
  }
}
