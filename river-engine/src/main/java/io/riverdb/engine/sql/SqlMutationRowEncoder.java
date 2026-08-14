package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns reusable row images for INSERT and UPDATE. */
final class SqlMutationRowEncoder {
  private final SqlExpressionEvaluator expressions;
  private final ByteBuffer insertRow =
      ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private final ByteBuffer sourceRow =
      ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private final ByteBuffer updatedRow =
      ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch decimalWide = new ExactDecimal.WideScratch();

  private int payloadOffset;
  private long nullMask;

  SqlMutationRowEncoder(SqlExpressionEvaluator evaluator) {
    expressions = evaluator;
  }

  ByteBuffer insertRow() {
    return insertRow;
  }

  ByteBuffer updatedRow() {
    return updatedRow;
  }

  StatusCode encodeInsert(
      SqlCommand command, BoundSqlStatement bound, int row) {
    TableDefinition table = bound.table;
    insertRow.clear();
    payloadOffset = table.fixedRowBytes();
    nullMask = 0;
    for (int column = 1; column < table.columnCount(); column++) {
      StatusCode status = encodeInsertColumn(command, bound, row, column);
      if (!status.isOk()) {
        return status;
      }
    }
    return finishRow(insertRow, table);
  }

  StatusCode encodeUpdate(
      SqlCommand command,
      BoundSqlStatement bound,
      HeapRowResult source,
      long primaryKey) {
    StatusCode status = copySourceRow(source, bound.table);
    if (!status.isOk()) {
      return status;
    }
    TableDefinition table = bound.table;
    updatedRow.clear();
    payloadOffset = table.fixedRowBytes();
    nullMask = sourceRow.getLong(table.nullMaskOffset());
    for (int column = 1; column < table.columnCount(); column++) {
      status = encodeUpdatedColumn(
          command, bound, source, primaryKey, column);
      if (!status.isOk()) {
        return status;
      }
    }
    return finishRow(updatedRow, table);
  }

  StatusCode validateRow(HeapRowResult source, TableDefinition definition) {
    return source.length() < definition.fixedRowBytes()
            || source.length() > definition.maximumRowBytes()
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  private StatusCode encodeInsertColumn(
      SqlCommand command,
      BoundSqlStatement bound,
      int row,
      int column) {
    TableDefinition table = bound.table;
    int source = bound.insertSourceByColumn[column];
    boolean omitted = source < 0;
    boolean useDefault = omitted
        ? table.hasDefault(column) : command.insertIsDefault(row, source);
    boolean nullValue = omitted
        ? !table.hasDefault(column) : command.insertIsNull(row, source);
    setNull(column, nullValue);
    int slot = (column - 1) * Long.BYTES;
    if (!table.isVarchar(column)) {
      long value = useDefault
          ? table.defaultValue(column) : command.insertValue(row, source);
      if (!useDefault
          && SqlTypeDescriptor.typeId(table.typeDescriptor(column))
              == SqlTypeDescriptor.TYPE_ID_DECIMAL
          && command.insertTypeDescriptor(row, source) != table.typeDescriptor(column)) {
        if (!ExactDecimal.widenScale(
            value,
            command.insertTypeDescriptor(row, source),
            table.typeDescriptor(column),
            decimal)) {
          return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
        }
        value = decimal.value;
      }
      insertRow.putLong(slot, value);
      return StatusCode.OK;
    }
    if (nullValue) {
      insertRow.putLong(slot, 0);
      return StatusCode.OK;
    }
    insertRow.position(payloadOffset);
    int bytes = useDefault
        ? table.copyDefaultText(column, insertRow)
        : command.copyText(command.insertValue(row, source), insertRow);
    return storeTextHandle(insertRow, slot, bytes);
  }

  private StatusCode encodeUpdatedColumn(
      SqlCommand command,
      BoundSqlStatement bound,
      HeapRowResult source,
      long primaryKey,
      int column) {
    int update = updateIndex(bound, column);
    boolean nullValue = update >= 0
        ? command.updateIsNull(update) : (nullMask & 1L << column) != 0;
    if (update >= 0 && command.isRelativeUpdate(update)) {
      nullValue = expressions.isNull(
          source, bound.table, bound.updateSourceColumns[update]);
      if (nullValue && !bound.table.isNullable(column)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    setNull(column, nullValue);
    return bound.table.isVarchar(column)
        ? encodeUpdatedText(command, bound, update, column, nullValue)
        : encodeUpdatedLong(
            command, bound, source, primaryKey, update, column, nullValue);
  }

  private StatusCode encodeUpdatedText(
      SqlCommand command,
      BoundSqlStatement bound,
      int update,
      int column,
      boolean nullValue) {
    int slot = (column - 1) * Long.BYTES;
    if (nullValue) {
      updatedRow.putLong(slot, 0);
      return StatusCode.OK;
    }
    updatedRow.position(payloadOffset);
    int bytes;
    if (update < 0) {
      bytes = copySourceText(slot);
    } else if (command.updateIsDefault(update)) {
      bytes = bound.table.copyDefaultText(column, updatedRow);
    } else {
      bytes = command.copyText(command.updateValue(update), updatedRow);
    }
    return storeTextHandle(updatedRow, slot, bytes);
  }

  private StatusCode encodeUpdatedLong(
      SqlCommand command,
      BoundSqlStatement bound,
      HeapRowResult source,
      long primaryKey,
      int update,
      int column,
      boolean nullValue) {
    int slot = (column - 1) * Long.BYTES;
    long value = nullValue ? 0 : update < 0
        ? sourceRow.getLong(slot)
        : command.updateIsDefault(update)
            ? bound.table.defaultValue(column) : command.updateValue(update);
    if (update >= 0 && command.isRelativeUpdate(update) && !nullValue) {
      long sourceValue = expressions.readColumn(
          primaryKey, source, bound.updateSourceColumns[update]);
      StatusCode status = evaluateUpdateExpression(
          command, bound, update, sourceValue);
      if (!status.isOk()) {
        return status;
      }
      value = decimal.value;
    }
    if (update >= 0 && !nullValue && !command.updateIsDefault(update)) {
      int sourceDescriptor = command.isRelativeUpdate(update)
          ? bound.updateResultTypeDescriptors[update]
          : command.updateTypeDescriptor(update);
      int targetDescriptor = bound.table.typeDescriptor(column);
      if (sourceDescriptor != targetDescriptor) {
        StatusCode status = ExactDecimal.quantize(
            value,
            sourceDescriptor,
            targetDescriptor,
            false,
            true,
            decimal,
            decimalWide);
        if (!status.isOk()) {
          return status;
        }
        value = decimal.value;
      }
    }
    updatedRow.putLong(slot, value);
    return StatusCode.OK;
  }

  private StatusCode evaluateUpdateExpression(
      SqlCommand command,
      BoundSqlStatement bound,
      int update,
      long sourceValue) {
    int sourceDescriptor = bound.table.typeDescriptor(
        bound.updateSourceColumns[update]);
    int targetDescriptor = bound.updateResultTypeDescriptors[update];
    long operand = command.updateValue(update);
    int operandDescriptor = command.updateTypeDescriptor(update);
    return switch (command.updateOperator(update)) {
      case SqlCommand.UPDATE_ADD -> ExactDecimal.add(
          sourceValue,
          sourceDescriptor,
          operand,
          operandDescriptor,
          false,
          targetDescriptor,
          decimal,
          decimalWide);
      case SqlCommand.UPDATE_SUBTRACT -> ExactDecimal.add(
          sourceValue,
          sourceDescriptor,
          operand,
          operandDescriptor,
          true,
          targetDescriptor,
          decimal,
          decimalWide);
      case SqlCommand.UPDATE_MULTIPLY -> ExactDecimal.multiply(
          sourceValue,
          sourceDescriptor,
          operand,
          operandDescriptor,
          targetDescriptor,
          decimal,
          decimalWide);
      case SqlCommand.UPDATE_DIVIDE -> ExactDecimal.divide(
          sourceValue,
          sourceDescriptor,
          operand,
          operandDescriptor,
          targetDescriptor,
          decimal,
          decimalWide);
      case SqlCommand.UPDATE_REMAINDER -> ExactDecimal.remainder(
          sourceValue,
          sourceDescriptor,
          operand,
          operandDescriptor,
          targetDescriptor,
          decimal,
          decimalWide);
      case SqlCommand.UPDATE_NEGATE ->
          ExactDecimal.negate(sourceValue, sourceDescriptor, decimal);
      case SqlCommand.UPDATE_ABSOLUTE ->
          ExactDecimal.absolute(sourceValue, sourceDescriptor, decimal);
      case SqlCommand.UPDATE_CEILING ->
          ExactDecimal.integral(sourceValue, sourceDescriptor, true, decimal);
      case SqlCommand.UPDATE_FLOOR ->
          ExactDecimal.integral(sourceValue, sourceDescriptor, false, decimal);
      case SqlCommand.UPDATE_ROUND -> ExactDecimal.quantize(
          sourceValue,
          sourceDescriptor,
          targetDescriptor,
          true,
          false,
          decimal,
          decimalWide);
      case SqlCommand.UPDATE_TRUNCATE -> ExactDecimal.quantize(
          sourceValue,
          sourceDescriptor,
          targetDescriptor,
          false,
          false,
          decimal,
          decimalWide);
      case SqlCommand.UPDATE_CAST -> ExactDecimal.quantize(
          sourceValue,
          sourceDescriptor,
          targetDescriptor,
          true,
          SqlTypeDescriptor.typeId(targetDescriptor)
              == SqlTypeDescriptor.TYPE_ID_BIGINT,
          decimal,
          decimalWide);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
  }

  private int copySourceText(int slot) {
    long handle = sourceRow.getLong(slot);
    int offset = (int) (handle >>> 32);
    int bytes = (int) handle;
    for (int index = 0; index < bytes; index++) {
      updatedRow.put(sourceRow.get(offset + index));
    }
    return bytes;
  }

  private StatusCode storeTextHandle(ByteBuffer row, int slot, int bytes) {
    if (bytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    row.putLong(
        slot, (long) payloadOffset << 32 | Integer.toUnsignedLong(bytes));
    payloadOffset += bytes;
    return StatusCode.OK;
  }

  private void setNull(int column, boolean value) {
    if (value) {
      nullMask |= 1L << column;
    } else {
      nullMask &= ~(1L << column);
    }
  }

  private StatusCode finishRow(ByteBuffer row, TableDefinition table) {
    row.putLong(table.nullMaskOffset(), nullMask);
    row.position(0);
    row.limit(payloadOffset);
    return table.isValidRow(row)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode copySourceRow(
      HeapRowResult source, TableDefinition table) {
    StatusCode status = validateRow(source, table);
    if (!status.isOk()) {
      return status;
    }
    sourceRow.clear();
    sourceRow.limit(source.length());
    status = source.copyTo(sourceRow);
    if (status.isOk()) {
      sourceRow.position(0);
      status = table.isValidRow(sourceRow)
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    return status;
  }

  private static int updateIndex(BoundSqlStatement bound, int column) {
    for (int index = 0; index < bound.updatedColumnCount; index++) {
      if (bound.updatedColumns[index] == column) {
        return index;
      }
    }
    return -1;
  }
}
