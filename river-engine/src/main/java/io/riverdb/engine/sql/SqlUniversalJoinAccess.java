package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Applies one bound cross-role access equality before the full ON program. */
final class SqlUniversalJoinAccess {
  private final ExactDecimal128.Scratch decimal = new ExactDecimal128.Scratch();
  private SqlBoundJoinContext context;

  void configure(SqlBoundJoinContext joinContext) { context = joinContext; }

  boolean matches(SqlUniversalJoinRows rows, int stage) {
    int outerRole = context.accessOuterRole(stage);
    int outerColumn = context.accessOuterColumn(stage);
    int innerColumn = context.accessInnerColumn(stage);
    if (outerRole < 0 || outerColumn < 0 || innerColumn < 0) return true;
    SqlBlockRow outer = rows.row(outerRole);
    SqlBlockRow inner = rows.row(stage + 1);
    if (outer == null || inner == null
        || outer.nullValue(outerColumn) || inner.nullValue(innerColumn)) return false;
    int outerType = context.table(outerRole).typeDescriptor(outerColumn);
    int innerType = context.table(stage + 1).typeDescriptor(innerColumn);
    if (!SqlNumericTypeRules.isNumeric(outerType)) {
      return outer.value(outerColumn) == inner.value(innerColumn);
    }
    if (!SqlTypeDescriptor.isWideDecimal(outerType)
        && !SqlTypeDescriptor.isWideDecimal(innerType)) {
      return SqlNumericValue.compare(
          outer.value(outerColumn), outerType,
          inner.value(innerColumn), innerType) == 0;
    }
    return SqlNumericComparison.compare(
        outer.highValue(outerColumn), outer.value(outerColumn), outerType,
        inner.highValue(innerColumn), inner.value(innerColumn), innerType,
        decimal) == 0;
  }
}
