package io.riverdb.sql;

/** Geometric command-column storage grown atomically to the admitted parse shape. */
final class SqlCommandCapacity {
  private SqlCommandCapacity() { }

  static boolean ensureColumns(SqlCommand command, int required) {
    if (required <= command.columnNames.length) return true;
    int capacity = command.columnNames.length;
    while (capacity < required) capacity = Math.min(SqlCommand.MAXIMUM_PROJECTIONS, capacity * 2);
    try {
      new SqlCommandColumnGrowth(command, capacity).publish(command);
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  static int maximumColumns(SqlCommandType type) {
    if (type == null) return SqlCommand.MAXIMUM_COLUMNS;
    return switch (type) {
      case SCAN, DISTINCT_SCAN, JOIN_SCAN, SELECT,
          COUNT, COUNT_VALUE, COUNT_DISTINCT, SUM, AVG, MIN, MAX,
          GROUP_COUNT, GROUP_COUNT_VALUE, GROUP_COUNT_DISTINCT,
          GROUP_SUM, GROUP_AVG, GROUP_MIN, GROUP_MAX ->
          SqlCommand.MAXIMUM_PROJECTIONS;
      default -> SqlCommand.MAXIMUM_COLUMNS;
    };
  }
}
