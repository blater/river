package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Streams or locally sorts one UNION leaf before common-schema coercion. */
final class SqlUnionLeafExecution {
  private final SqlBlockOutputOrder ordering = new SqlBlockOutputOrder();
  private final SqlBlockRowStore ordered;
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockRow converted = new SqlBlockRow();
  private final SqlUnionRowCoercion coercion = new SqlUnionRowCoercion();
  private SqlUnionLeafSource source;
  private SqlBlockSchema outputSchema;

  SqlUnionLeafExecution(SqlSessionShapeBudget budget) {
    ordered = new SqlBlockRowStore(budget);
  }

  StatusCode prepare(SqlUnionLeafSource leafSource, SqlBlockSchema schema) {
    source = leafSource;
    outputSchema = schema;
    return coercion.prepare();
  }

  StatusCode append(int block, SqlCommand command, SqlBlockRowStore output) {
    StatusCode status = source.open(block);
    if (!status.isOk()) return source.close(status);
    SqlBlockSchema input = source.schema();
    if (input == null) return source.close(StatusCode.INVALID_EXTERNAL_INPUT);
    if (source.finalized()) {
      return appendStreaming(Long.MAX_VALUE, input, output);
    }
    return command.isOrdered()
        ? appendOrdered(command, input, output)
        : appendStreaming(command.rowLimit(), input, output);
  }

  private StatusCode appendStreaming(
      long limit, SqlBlockSchema input, SqlBlockRowStore output) {
    StatusCode status = StatusCode.OK;
    long rows = 0;
    while (status.isOk() && rows < limit) {
      status = source.next(sourceRow);
      if (status == StatusCode.CONFLICT) { status = StatusCode.OK; break; }
      if (status.isOk()) status = appendConverted(sourceRow, input, output);
      if (status.isOk()) rows++;
    }
    return source.close(status);
  }

  private StatusCode appendOrdered(
      SqlCommand command, SqlBlockSchema input, SqlBlockRowStore output) {
    StatusCode status = ordering.beginOutput(command, input, ordered);
    while (status.isOk()) {
      status = source.next(sourceRow);
      if (status == StatusCode.CONFLICT) { status = StatusCode.OK; break; }
      if (status.isOk()) status = ordered.append(sourceRow);
    }
    status = source.close(status);
    if (status.isOk()) status = ordered.finish();
    if (status.isOk()) status = ordered.limit(command.rowLimit());
    while (status.isOk()) {
      status = ordered.next(sourceRow);
      if (status == StatusCode.CONFLICT) { status = StatusCode.OK; break; }
      if (status.isOk()) status = appendConverted(sourceRow, input, output);
    }
    StatusCode closed = ordered.close();
    return status.isOk() ? closed : status;
  }

  private StatusCode appendConverted(
      SqlBlockRow row, SqlBlockSchema input, SqlBlockRowStore output) {
    StatusCode status = coercion.convert(row, input, outputSchema, converted);
    return status.isOk() ? output.append(converted) : status;
  }

  StatusCode close() { return ordered.close(); }
}
