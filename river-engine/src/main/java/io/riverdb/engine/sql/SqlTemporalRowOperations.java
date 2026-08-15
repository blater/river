package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Primitive row cast, zone conversion, and canonical temporal text operations. */
final class SqlTemporalRowOperations {
  private final SqlTemporalZonePlan sessionZone;
  private final LocalTemporal.Value castValue = new LocalTemporal.Value();

  SqlTemporalRowOperations(SqlTemporalZonePlan sessionZonePlan) {
    sessionZone = sessionZonePlan;
  }

  StatusCode cast(
      long value,
      int source,
      int target,
      SqlTemporalContext.LongResult result) {
    StatusCode status = validate(value, source);
    if (!status.isOk()) return status;
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        && targetType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) {
      status = sessionZone.localToInstant(value, result);
      return status.isOk() ? validate(result.value, target) : status;
    }
    if (sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        && targetType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP) {
      status = sessionZone.instantToLocal(value, result);
      return status.isOk() ? validate(result.value, target) : status;
    }
    status = LocalTemporalCast.castFixed(
        value, source, target, castValue);
    result.value = castValue.value;
    return status;
  }

  StatusCode atTimeZone(
      long value,
      int source,
      SqlTemporalZonePlan zone,
      SqlTemporalContext.LongResult result) {
    StatusCode status = validate(value, source);
    if (!status.isOk()) return status;
    int sourceType = SqlTypeDescriptor.typeId(source);
    status = sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        ? zone.localToInstant(value, result)
        : sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            ? zone.instantToLocal(value, result) : StatusCode.DATATYPE_MISMATCH;
    int precision = SqlTypeDescriptor.parameterOne(source);
    int target = sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        ? SqlTypeDescriptor.timestampWithTimeZone(precision)
        : SqlTypeDescriptor.timestamp(precision);
    return status.isOk() ? validate(result.value, target) : status;
  }

  StatusCode format(
      long value,
      int source,
      int target,
      char[] characters,
      LocalTemporalCast.TextResult result) {
    if (result != null) result.length = 0;
    if (result == null
        || characters == null
        || !SqlTypeDescriptor.isValid(target)
        || SqlTypeDescriptor.typeId(target) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return LocalTemporalCast.formatText(
        value, source, target, characters, 0, result);
  }

  private StatusCode validate(long value, int descriptor) {
    return LocalTemporalCast.castFixed(
        value, descriptor, descriptor, castValue);
  }
}
