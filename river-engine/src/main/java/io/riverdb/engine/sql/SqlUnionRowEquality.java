package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Null-aware SQL equality for rows already coerced to one UNION schema. */
final class SqlUnionRowEquality {
  private SqlUnionRowEquality() { }

  static boolean same(
      SqlBlockRow left, SqlBlockRow right, SqlBlockSchema schema) {
    for (int column = 0; column < schema.count(); column++) {
      if (!same(left, right, schema.descriptor(column), column)) return false;
    }
    return true;
  }

  private static boolean same(
      SqlBlockRow left, SqlBlockRow right, int descriptor, int column) {
    if (left.nullValue(column) != right.nullValue(column)) return false;
    if (left.nullValue(column)) return true;
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return sameText(left, right, column);
    }
    if (SqlNumericTypeRules.isNumeric(descriptor)
        && !SqlTypeDescriptor.isWideDecimal(descriptor)) {
      return SqlNumericValue.compare(
          left.value(column), descriptor,
          right.value(column), descriptor) == 0;
    }
    return left.highValue(column) == right.highValue(column)
        && left.value(column) == right.value(column);
  }

  private static boolean sameText(
      SqlBlockRow left, SqlBlockRow right, int column) {
    int length = left.textLength(column);
    if (length != right.textLength(column)) return false;
    for (int index = 0; index < length; index++) {
      if (left.textCharacter(column, index)
          != right.textCharacter(column, index)) return false;
    }
    return true;
  }
}
