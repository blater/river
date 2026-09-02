package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Shared binding and metadata rules for one selected primitive sort key. */
final class SqlPrimitiveSortKey {
  private SqlPrimitiveSortKey() {}

  static StatusCode validate(
      SqlCommand command, BoundSqlStatement bound, int projection) {
    SqlScalarExpression expression = command.projectionExpression(projection);
    if (expression == null || !expression.hasColumnReference()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int family = SqlTypeDescriptor.comparisonFamily(
        bound.projectionPrograms.resultDescriptor(projection));
    if (family == SqlTypeDescriptor.COMPARISON_TEXT) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return SqlNumericTypeRules.isNumeric(
            bound.projectionPrograms.resultDescriptor(projection))
            || family == SqlTypeDescriptor.COMPARISON_LOCAL_TEMPORAL
            || family == SqlTypeDescriptor.COMPARISON_INSTANT
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  static boolean computed(int column) {
    return column == SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
  }

  static int descriptor(BoundSqlStatement bound, int column) {
    return computed(column)
        ? bound.projectedTypeDescriptors[0]
        : bound.table.typeDescriptor(column);
  }

  static CharSequence outputName(
      BoundSqlQuery.Block command, int column) {
    CharSequence name = command.columnOutputName(0);
    return name.length() == 0 && computed(column) ? "expression" : name;
  }
}
