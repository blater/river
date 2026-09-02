package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlQuery;

/** Shared admission policy for one durable, executable view definition. */
final class SqlStoredViewPolicy {
  private SqlStoredViewPolicy() {}

  static StatusCode validate(SqlCommand command, SqlQuery query) {
    if (query.hasNestedTopology() || query.isExplain()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (query.sourcePlanDepth() >= SqlQuery.MAXIMUM_QUERY_BLOCKS) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    if (!shape(command)
        || !hasUniqueOutputNames(command)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  static StatusCode validateExpanded(SqlCommand command) {
    return shape(command) && hasUniqueOutputNames(command)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static boolean shape(SqlCommand command) {
    return admittedType(command.type())
        && !command.isSelectAll()
        && command.columnCount() > 0
        && !command.isOrdered()
        && command.rowLimit() == Long.MAX_VALUE;
  }

  static StatusCode validateZones(
      SqlCommand command, SqlQuery query, SqlTemporalZoneNames zones) {
    return SqlStoredViewZonePolicy.validate(command, query, zones);
  }

  static StatusCode validateZones(
      SqlQuery query, int firstBlock, SqlTemporalZoneNames zones) {
    return SqlStoredViewZonePolicy.validate(query, firstBlock, zones);
  }

  private static boolean admittedType(SqlCommandType type) {
    if (type == SqlCommandType.SCAN
        || type == SqlCommandType.SELECT
        || type == SqlCommandType.JOIN_SCAN) return true;
    return type == SqlCommandType.DISTINCT_SCAN
        || type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.COUNT_DISTINCT
        || type == SqlCommandType.SUM
        || type == SqlCommandType.AVG
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX
        || type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_COUNT_DISTINCT
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  private static boolean hasUniqueOutputNames(SqlCommand command) {
    for (int index = 0; index < command.columnCount(); index++) {
      CharSequence name = command.columnOutputName(index);
      if (name.length() == 0) return false;
      for (int previous = 0; previous < index; previous++) {
        if (SqlBindingNames.same(name, command.columnOutputName(previous))) {
          return false;
        }
      }
    }
    return true;
  }
}
