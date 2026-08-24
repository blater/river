package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlComparison;

/** Converts one access literal into a target fixed descriptor. */
final class SqlAccessEdgeConversion {
  private SqlAccessEdgeConversion() { }

  static boolean convert(SqlAccessEdgeSelector target, long value, int source, int descriptor,
      SqlComparison comparison) {
    if (source == descriptor || !numeric(source) || !numeric(descriptor)) {
      target.convertedValue = value;
      return true;
    }
    boolean converted = comparison == SqlComparison.EQUAL
        ? SqlTypeDescriptor.canImplicitlyCast(source, descriptor)
            ? ExactDecimal.widenScale(value, source, descriptor, target.decimal)
            : ExactDecimal.quantize(value, source, descriptor, false, true,
                target.decimal, target.wide).isOk()
        : ExactDecimal.ceilingScale(value, source, descriptor, target.decimal);
    if (converted) target.convertedValue = target.decimal.value;
    return converted;
  }

  private static boolean numeric(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_BIGINT || type == SqlTypeDescriptor.TYPE_ID_DECIMAL;
  }
}
