package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Applies the cardinality-stage shape policy without mutating query carriers. */
final class SqlDerivedPipelinePolicy {
  private SqlDerivedPipelinePolicy() {}

  static StatusCode validate(
      SqlQuery query, SqlDerivedReferenceValidator references, int firstBlock) {
    if (query.blockCount() < 2 || firstBlock < 0 || firstBlock >= query.blockCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = firstBlock; index < query.blockCount(); index++) {
      SqlCommand block = query.block(index);
      StatusCode status = shape(block, index, query.blockCount() - 1);
      if (status.isOk()) status = references.validate(index, true);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode shape(SqlCommand block, int index, int deepest) {
    if (index > 0 && block.isOrdered()
        || block.type() == SqlCommandType.JOIN_SCAN && index != deepest) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return admitted(block.type())
            && !block.isSelectAll()
            && block.columnCount() > 0
            && (index == 0 || block.rowLimit() == Long.MAX_VALUE)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean admitted(SqlCommandType type) {
    return type == SqlCommandType.SCAN
        || type == SqlCommandType.SELECT
        || type == SqlCommandType.JOIN_SCAN
        || type == SqlCommandType.DISTINCT_SCAN
        || type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.SUM
        || type == SqlCommandType.AVG
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX
        || type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }
}
