package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableStatistics;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Estimates one simple integer filter from analyzed column statistics. */
final class SqlJoinOrderFilterCost {
  private final SqlJoinRoleResolver symbols = new SqlJoinRoleResolver();
  private int column;
  private double rows;
  private boolean equality;

  boolean estimate(SqlCommand command, SqlBoundJoinContext context, int role, int leaf) {
    SqlBooleanPredicateProgram where = command.wherePredicates();
    if (where.leafNegated(leaf)
        || where.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON) return false;
    int columnSide = where.programNodeCount(leaf, 0) == 1
        && where.programOperator(leaf, 0, 0) == SqlScalarExpression.COLUMN ? 0 : 1;
    int literalSide = 1 - columnSide;
    if (where.programNodeCount(leaf, columnSide) != 1
        || where.programNodeCount(leaf, literalSide) != 1
        || where.programOperator(leaf, columnSide, 0) != SqlScalarExpression.COLUMN
        || where.programOperator(leaf, literalSide, 0) != SqlScalarExpression.LITERAL) {
      return false;
    }
    int symbol = (int) where.programOperand(leaf, columnSide, 0);
    if (!symbols.resolve(command, context, symbol, 2) || symbols.role() != role) {
      return false;
    }
    column = symbols.column();
    TableDefinition table = context.table(role);
    if (!integer(table.typeDescriptor(column))
        || !integer(where.programDescriptor(leaf, literalSide, 0))) return false;
    SqlComparison comparison = where.comparison(leaf);
    if (columnSide == 1) comparison = comparison.reverseOrder();
    equality = comparison == SqlComparison.EQUAL;
    TableStatistics statistics = context.statistics(role);
    long value = where.programOperand(leaf, literalSide, 0);
    rows = equality ? equalityRows(statistics, column)
        : rangeRows(statistics, column, comparison, value);
    return rows >= 0;
  }

  int column() { return column; }
  double rows() { return rows; }
  boolean equality() { return equality; }

  private static double equalityRows(TableStatistics statistics, int column) {
    long distinct = statistics.distinctCount(column);
    return distinct > 0 ? (double) statistics.rowCount() / distinct : -1;
  }

  private static double rangeRows(
      TableStatistics statistics, int column, SqlComparison comparison, long value) {
    if (!statistics.hasMinMax(column)) return -1;
    long minimum = statistics.minimumValue(column);
    long maximum = statistics.maximumValue(column);
    if (minimum > maximum) return -1;
    double span = (double) maximum - minimum + 1;
    double count = switch (comparison) {
      case LESS_THAN -> (double) value - minimum;
      case LESS_OR_EQUAL -> (double) value - minimum + 1;
      case GREATER_THAN -> (double) maximum - value;
      case GREATER_OR_EQUAL -> (double) maximum - value + 1;
      default -> Double.NaN;
    };
    if (Double.isNaN(count)) return -1;
    return statistics.rowCount() * Math.max(0, Math.min(1, count / span));
  }

  private static boolean integer(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_SMALLINT
        || type == SqlTypeDescriptor.TYPE_ID_INTEGER
        || type == SqlTypeDescriptor.TYPE_ID_BIGINT;
  }
}
