package io.riverdb.engine.sql;

import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlIdentifier;

/** Resolves aggregate result names without owning execution state. */
final class SqlResultMetadata {
  private static final String COUNT_COLUMN_NAME = "count";
  private static final String SUM_COLUMN_NAME = "sum";
  private static final String AVG_COLUMN_NAME = "avg";
  private static final String MIN_COLUMN_NAME = "min";
  private static final String MAX_COLUMN_NAME = "max";
  private static final String EXPRESSION_COLUMN_NAME = "expression";

  CharSequence aggregateColumnName(SqlCommand command) {
    SqlIdentifier alias = command.columnAlias(0);
    if (alias != null && alias.length() > 0) {
      return alias;
    }
    return switch (command.type()) {
      case SUM -> SUM_COLUMN_NAME;
      case AVG -> AVG_COLUMN_NAME;
      case SCALAR_EXPRESSION -> EXPRESSION_COLUMN_NAME;
      case MIN -> MIN_COLUMN_NAME;
      case MAX -> MAX_COLUMN_NAME;
      default -> COUNT_COLUMN_NAME;
    };
  }

  CharSequence groupAggregateColumnName(SqlCommand command) {
    SqlIdentifier alias = command.columnAlias(1);
    if (command.type() != SqlCommandType.GROUP_COUNT
        && alias != null
        && alias.length() > 0) {
      return alias;
    }
    return switch (command.type()) {
      case GROUP_SUM -> SUM_COLUMN_NAME;
      case GROUP_AVG -> AVG_COLUMN_NAME;
      case GROUP_MIN -> MIN_COLUMN_NAME;
      case GROUP_MAX -> MAX_COLUMN_NAME;
      default -> COUNT_COLUMN_NAME;
    };
  }

  static CharSequence invocationColumnName(
      SqlCommand command, int column, int aggregateKind) {
    SqlIdentifier alias = command.columnAlias(column);
    if (alias != null && alias.length() > 0) return alias;
    return switch (aggregateKind) {
      case SqlAggregateKind.SUM -> SUM_COLUMN_NAME;
      case SqlAggregateKind.AVG -> AVG_COLUMN_NAME;
      case SqlAggregateKind.MIN -> MIN_COLUMN_NAME;
      case SqlAggregateKind.MAX -> MAX_COLUMN_NAME;
      default -> COUNT_COLUMN_NAME;
    };
  }
}
