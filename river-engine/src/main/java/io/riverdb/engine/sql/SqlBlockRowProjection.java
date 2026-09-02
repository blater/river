package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Evaluates one prepared projection set into a dynamically admitted block row. */
final class SqlBlockRowProjection {
  private SqlBlockRowProjection() { }

  static StatusCode project(
      SqlBlockRow source,
      SqlBlockRow result,
      BoundSqlStatement bound,
      SqlExpressionEvaluator columns,
      SqlRowExpressionEvaluator expressions,
      SqlProjectionZoneSet zones) {
    return project(source, result, bound, columns, expressions, zones, -1);
  }

  static StatusCode project(
      SqlBlockRow source,
      SqlBlockRow result,
      BoundSqlStatement bound,
      SqlExpressionEvaluator columns,
      SqlRowExpressionEvaluator expressions,
      SqlProjectionZoneSet zones,
      int block) {
    StatusCode status = result.reset(bound.projectionPrograms.count());
    if (status.isOk()) result.setKey(source.key());
    for (int projection = 0;
        status.isOk() && projection < bound.projectionPrograms.count(); projection++) {
      if (block >= 0 && !bound.blockPlans().projectionLive(block, projection)) {
        result.setNull(projection);
        continue;
      }
      int raw = bound.projectionPrograms.rawColumn(projection);
      if (raw >= 0) {
        if (source.nullValue(raw)) result.setNull(projection);
        else {
          int descriptor = bound.projectionPrograms.resultDescriptor(projection);
          if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
            result.setDecimal128(
                projection, source.highValue(raw), source.value(raw));
          } else result.setValue(projection, source.value(raw));
          if (SqlTypeDescriptor.typeId(descriptor)
              == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
            result.setText(projection, source.text(raw), 0, source.textLength(raw));
          }
        }
      } else {
        status = expressions.evaluateBlock(
            bound.command,
            bound.projectionPrograms,
            projection,
            zones.get(projection),
            source,
            result);
      }
    }
    return status.isOk() ? result.status() : status;
  }
}
