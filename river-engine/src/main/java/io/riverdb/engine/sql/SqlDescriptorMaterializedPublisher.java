package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Publishes retained descriptor rows without advancing on output failure. */
final class SqlDescriptorMaterializedPublisher {
  private SqlDescriptorMaterializedPublisher() { }

  static StatusCode next(
      SqlDescriptorScanContext context, SqlScanCursor cursor, SqlScanRowResult result) {
    while (!cursor.limitReached()) {
      StatusCode status = context.ordered.read();
      if (!status.isOk()) return status;
      SqlBlockRow row = context.ordered.row();
      if (context.forUpdate) {
        status = context.ordered.lockCurrent(context.session);
        if (!status.isOk()) return status;
        if (!context.ordered.candidateLocked()) {
          context.ordered.advance();
          continue;
        }
        status = context.evaluatePredicate(context.ordered.currentValues());
        if (!status.isOk()) return release(context, status);
        if (!context.predicateMatched()) {
          status = release(context, StatusCode.OK);
          if (!status.isOk()) return status;
          context.ordered.advance();
          continue;
        }
        row = context.ordered.row();
      }
      status = context.projection.publishScan(
          row, SqlDescriptorPublicRowKey.from(context.ordered.table(), row), result);
      if (context.forUpdate) {
        if (status.isOk()) status = context.session.descriptorRows().retainCurrent();
        else status = release(context, status);
      }
      if (status.isOk()) {
        context.ordered.advance();
        cursor.rowReturned();
      }
      return status;
    }
    return StatusCode.CONFLICT;
  }

  private static StatusCode release(
      SqlDescriptorScanContext context, StatusCode original) {
    if (!context.session.descriptorRows().currentBorrowed()) return original;
    StatusCode released = context.session.descriptorRows().releaseCurrent();
    return released.isOk() ? original : released;
  }
}
