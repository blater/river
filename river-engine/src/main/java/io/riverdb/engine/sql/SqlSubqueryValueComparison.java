package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlComparison;

/** Common allocation-free comparison for live and retained subquery values. */
final class SqlSubqueryValueComparison {
  private SqlSubqueryValueComparison() { }

  static boolean matches(
      SqlExpressionEvaluator expressions,
      SqlPredicateOperand left,
      SqlPredicateOperand right,
      SqlComparison comparison) {
    int compared = SqlTypeDescriptor.typeId(left.descriptor())
            == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? SqlBooleanTextComparator.compare(left, right)
        : expressions.compareExact(
            left.highValue(), left.value(), left.descriptor(),
            right.highValue(), right.value(), right.descriptor());
    return SqlDescriptorComparison.matches(compared, comparison);
  }
}
