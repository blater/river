package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;

/** Resolves defaults and exact fixed-width assignment coercions. */
final class SqlMutationFixedValues {
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private final SqlTemporalContext temporal;
  private final SqlTemporalContext.LongResult result =
      new SqlTemporalContext.LongResult();

  SqlMutationFixedValues(SqlTemporalContext context) {
    temporal = context;
  }

  long value() {
    return result.value;
  }

  void set(long value) {
    result.value = value;
  }

  StatusCode defaultValue(TableDefinition table, int column) {
    int kind = table.defaultKind(column);
    if (SqlDefaultKind.isCurrent(kind)) {
      return temporal.defaultValue(kind, table.typeDescriptor(column), result);
    }
    result.value = table.defaultValue(column);
    return StatusCode.OK;
  }

  StatusCode coerce(long value, int source, int target) {
    if (sameLocalTemporalType(source, target)) {
      result.value = value;
      return StatusCode.OK;
    }
    StatusCode status = ExactDecimal.quantize(
        value, source, target, false, true, decimal, wide);
    if (status.isOk()) result.value = decimal.value;
    return status;
  }

  StatusCode widenDecimal(long value, int source, int target) {
    if (!ExactDecimal.widenScale(value, source, target, decimal)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = decimal.value;
    return StatusCode.OK;
  }

  private static boolean sameLocalTemporalType(int source, int target) {
    int sourceType = SqlTypeDescriptor.typeId(source);
    return sourceType == SqlTypeDescriptor.typeId(target)
        && (sourceType == SqlTypeDescriptor.TYPE_ID_TIME
            || sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            || sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE);
  }
}
