package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlIdentifier;

/** Resolves aggregate result names without owning execution state. */
final class SqlResultMetadata {
  private static final String COUNT_COLUMN_NAME = "count";
  private static final String SUM_COLUMN_NAME = "sum";
  private static final String MIN_COLUMN_NAME = "min";
  private static final String MAX_COLUMN_NAME = "max";

  CharSequence aggregateColumnName(SqlCommand command) {
    SqlIdentifier alias = command.columnAlias(0);
    if (alias != null && alias.length() > 0) {
      return alias;
    }
    return command.type() == SqlCommandType.SUM
        ? SUM_COLUMN_NAME
        : command.type() == SqlCommandType.MIN
            ? MIN_COLUMN_NAME
            : command.type() == SqlCommandType.MAX
                ? MAX_COLUMN_NAME : COUNT_COLUMN_NAME;
  }

  CharSequence groupAggregateColumnName(SqlCommand command) {
    SqlIdentifier alias = command.columnAlias(1);
    if (command.type() != SqlCommandType.GROUP_COUNT
        && alias != null
        && alias.length() > 0) {
      return alias;
    }
    return command.type() == SqlCommandType.GROUP_SUM
        ? SUM_COLUMN_NAME
        : command.type() == SqlCommandType.GROUP_MIN
            ? MIN_COLUMN_NAME
            : command.type() == SqlCommandType.GROUP_MAX
                ? MAX_COLUMN_NAME : COUNT_COLUMN_NAME;
  }
}
