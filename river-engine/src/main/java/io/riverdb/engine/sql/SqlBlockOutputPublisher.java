package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Writes one already-materialized block row directly into a reserved result carrier. */
final class SqlBlockOutputPublisher {
  private SqlBlockOutputPublisher() { }

  static StatusCode next(
      SqlBlockRowStore store,
      SqlBlockRow source,
      SqlBlockSchema schema,
      SqlBlockOutputShape shape,
      SqlScanRowResult result) {
    StatusCode status = store.next(source);
    if (status.isOk()) status = shape.begin(result, source.key());
    if (status.isOk()) status = fixed(source, schema, shape.count(), result);
    return status.isOk() ? text(source, schema, shape.count(), result) : status;
  }

  static StatusCode next(
      SqlBlockRowStore store,
      SqlBlockRow source,
      SqlBlockSchema schema,
      SqlBlockOutputShape shape,
      long commitSequence,
      SqlExecutionResult result) {
    StatusCode status = store.next(source);
    if (status.isOk()) status = shape.begin(result, source.key(), commitSequence);
    if (status.isOk()) status = fixed(source, schema, shape.count(), result);
    return status.isOk() ? text(source, schema, shape.count(), result) : status;
  }

  private static StatusCode fixed(
      SqlBlockRow source, SqlBlockSchema schema, int count, SqlScanRowResult result) {
    for (int column = 0; column < count; column++) {
      if (source.nullValue(column)) result.setProjectedNull(column);
      else if (SqlTypeDescriptor.isWideDecimal(schema.descriptor(column))) {
        result.setProjectedDecimal128(
            column, source.highValue(column), source.value(column));
      } else result.setProjectedValue(column, source.value(column));
    }
    return source.status();
  }

  private static StatusCode fixed(
      SqlBlockRow source, SqlBlockSchema schema, int count, SqlExecutionResult result) {
    for (int column = 0; column < count; column++) {
      if (source.nullValue(column)) result.setProjectedNull(column);
      else if (SqlTypeDescriptor.isWideDecimal(schema.descriptor(column))) {
        result.setProjectedDecimal128(
            column, source.highValue(column), source.value(column));
      } else result.setProjectedValue(column, source.value(column));
    }
    return source.status();
  }

  private static StatusCode text(
      SqlBlockRow source, SqlBlockSchema schema, int count, SqlScanRowResult result) {
    StatusCode status = StatusCode.OK;
    for (int column = 0; status.isOk() && column < count; column++) {
      if (schema.varchar(column) && !source.nullValue(column)) {
        status = result.setTextAt(
            column, source.text(column), 0, source.textLength(column));
      }
    }
    return status;
  }

  private static StatusCode text(
      SqlBlockRow source, SqlBlockSchema schema, int count, SqlExecutionResult result) {
    StatusCode status = StatusCode.OK;
    for (int column = 0; status.isOk() && column < count; column++) {
      if (schema.varchar(column) && !source.nullValue(column)) {
        status = result.setTextAt(column, source.text(column), source.textLength(column));
      }
    }
    return status;
  }
}
