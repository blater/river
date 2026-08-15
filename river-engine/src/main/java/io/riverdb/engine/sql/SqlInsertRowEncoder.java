package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import java.nio.ByteBuffer;

/** Owns the reusable INSERT row image and computed primary-key result. */
final class SqlInsertRowEncoder {
  private final ByteBuffer row =
      ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private final SqlRowProjectionEvaluator expressions;
  private final SqlMutationFixedValues fixedValues;
  private int payloadOffset;
  private long nullMask;
  private long key;

  SqlInsertRowEncoder(
      SqlRowProjectionEvaluator evaluator, SqlMutationFixedValues values) {
    expressions = evaluator;
    fixedValues = values;
  }

  ByteBuffer row() {
    return row;
  }

  long key() {
    return key;
  }

  StatusCode resolveKey(SqlCommand command, BoundSqlStatement bound, int tuple) {
    int source = bound.insertSourceByColumn[0];
    if (!command.insertHasExpression(tuple, source)) {
      key = command.insertValue(tuple, source);
      return StatusCode.OK;
    }
    StatusCode status = expressions.evaluateMutation(
        command.insertExpression(tuple, source), 0, null);
    if (!status.isOk() || expressions.resultNull()) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    status = fixedValues.coerce(
        expressions.resultValue(),
        expressions.resultDescriptor(),
        bound.table.typeDescriptor(0));
    if (status.isOk()) key = fixedValues.value();
    return status;
  }

  StatusCode encode(SqlCommand command, BoundSqlStatement bound, int tuple) {
    TableDefinition table = bound.table;
    row.clear();
    payloadOffset = table.fixedRowBytes();
    nullMask = 0;
    for (int column = 1; column < table.columnCount(); column++) {
      StatusCode status = encodeColumn(command, bound, tuple, column);
      if (!status.isOk()) return status;
    }
    row.putLong(table.nullMaskOffset(), nullMask);
    row.position(0);
    row.limit(payloadOffset);
    return table.isValidRow(row)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode encodeColumn(
      SqlCommand command, BoundSqlStatement bound, int tuple, int column) {
    TableDefinition table = bound.table;
    int source = bound.insertSourceByColumn[column];
    boolean omitted = source < 0;
    boolean useDefault = omitted
        ? table.hasDefault(column) : command.insertIsDefault(tuple, source);
    boolean nullValue = omitted
        ? !table.hasDefault(column) : command.insertIsNull(tuple, source);
    boolean computed = source >= 0 && command.insertHasExpression(tuple, source);
    if (computed) {
      StatusCode status = expressions.evaluateMutation(
          command.insertExpression(tuple, source), 0, null);
      if (!status.isOk()) return status;
      nullValue = expressions.resultNull();
      if (nullValue && !table.isNullable(column)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    setNull(column, nullValue);
    int slot = (column - 1) * Long.BYTES;
    if (!table.isVarchar(column)) {
      return encodeFixed(
          command, table, tuple, source, column, slot,
          useDefault, nullValue, computed);
    }
    return encodeText(
        command, table, tuple, source, column, slot, useDefault, nullValue);
  }

  private StatusCode encodeFixed(
      SqlCommand command,
      TableDefinition table,
      int tuple,
      int source,
      int column,
      int slot,
      boolean useDefault,
      boolean nullValue,
      boolean computed) {
    if (nullValue) {
      row.putLong(slot, 0);
      return StatusCode.OK;
    }
    long value;
    if (computed) {
      value = expressions.resultValue();
      int target = table.typeDescriptor(column);
      if (expressions.resultDescriptor() != target) {
        StatusCode status = fixedValues.coerce(
            value, expressions.resultDescriptor(), target);
        if (!status.isOk()) return status;
        value = fixedValues.value();
      }
    } else if (useDefault) {
      StatusCode status = fixedValues.defaultValue(table, column);
      if (!status.isOk()) return status;
      value = fixedValues.value();
    } else {
      value = command.insertValue(tuple, source);
      int supplied = command.insertTypeDescriptor(tuple, source);
      int target = table.typeDescriptor(column);
      if (SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_DECIMAL
          && supplied != target) {
        StatusCode status = fixedValues.widenDecimal(value, supplied, target);
        if (!status.isOk()) return status;
        value = fixedValues.value();
      }
    }
    row.putLong(slot, value);
    return StatusCode.OK;
  }

  private StatusCode encodeText(
      SqlCommand command,
      TableDefinition table,
      int tuple,
      int source,
      int column,
      int slot,
      boolean useDefault,
      boolean nullValue) {
    if (nullValue) {
      row.putLong(slot, 0);
      return StatusCode.OK;
    }
    row.position(payloadOffset);
    int bytes = useDefault
        ? table.copyDefaultText(column, row)
        : command.copyText(command.insertValue(tuple, source), row);
    if (bytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    row.putLong(slot, (long) payloadOffset << 32 | Integer.toUnsignedLong(bytes));
    payloadOffset += bytes;
    return StatusCode.OK;
  }

  private void setNull(int column, boolean value) {
    if (value) nullMask |= 1L << column;
    else nullMask &= ~(1L << column);
  }
}
