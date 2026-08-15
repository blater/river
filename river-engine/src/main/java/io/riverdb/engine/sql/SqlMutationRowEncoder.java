package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns reusable row images for INSERT and UPDATE. */
final class SqlMutationRowEncoder {
  private final SqlRowProjectionEvaluator rowExpressions;
  private final ByteBuffer sourceRow =
      ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private final ByteBuffer updatedRow =
      ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
  private final SqlMutationFixedValues fixedValues;
  private final SqlInsertRowEncoder inserts;

  private int payloadOffset;
  private long nullMask;
  private long mutationValue;
  private int mutationDescriptor;
  private boolean mutationNull;

  SqlMutationRowEncoder(
      SqlTemporalContext temporalContext,
      SqlRowProjectionEvaluator mutationExpressions) {
    fixedValues = new SqlMutationFixedValues(temporalContext);
    rowExpressions = mutationExpressions;
    inserts = new SqlInsertRowEncoder(mutationExpressions, fixedValues);
  }

  ByteBuffer insertRow() {
    return inserts.row();
  }

  ByteBuffer updatedRow() {
    return updatedRow;
  }

  long insertKey() {
    return inserts.key();
  }

  StatusCode resolveInsertKey(
      SqlCommand command, BoundSqlStatement bound, int row) {
    return inserts.resolveKey(command, bound, row);
  }

  StatusCode encodeInsert(
      SqlCommand command, BoundSqlStatement bound, int row) {
    return inserts.encode(command, bound, row);
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

  private StatusCode encodeUpdatedColumn(
      SqlCommand command,
      BoundSqlStatement bound,
      HeapRowResult source,
      long primaryKey,
      int column) {
    int update = updateIndex(bound, column);
    boolean nullValue = update >= 0
        ? command.updateIsNull(update) : (nullMask & 1L << column) != 0;
    if (update >= 0 && command.updateHasExpression(update)) {
      StatusCode status = evaluateMutation(
          command.updateExpression(update), primaryKey, source, bound);
      if (!status.isOk()) return status;
      nullValue = mutationNull;
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
    StatusCode status = resolveUpdatedLong(
        command, bound.table, update, column, slot, nullValue);
    if (!status.isOk()) {
      return status;
    }
    long value = fixedValues.value();
    if (update >= 0 && command.updateHasExpression(update) && !nullValue) {
      value = mutationValue;
    }
    if (update >= 0 && !nullValue && !command.updateIsDefault(update)) {
      int sourceDescriptor = command.updateHasExpression(update)
          ? mutationDescriptor
          : command.updateTypeDescriptor(update);
      int targetDescriptor = bound.table.typeDescriptor(column);
      if (sourceDescriptor != targetDescriptor) {
        status = coerceMutation(value, sourceDescriptor, targetDescriptor);
        if (!status.isOk()) {
          return status;
        }
        value = fixedValues.value();
      }
    }
    updatedRow.putLong(slot, value);
    return StatusCode.OK;
  }

  private StatusCode resolveUpdatedLong(
      SqlCommand command,
      TableDefinition table,
      int update,
      int column,
      int slot,
      boolean nullValue) {
    if (nullValue) {
      fixedValues.set(0);
      return StatusCode.OK;
    }
    if (update < 0) {
      fixedValues.set(sourceRow.getLong(slot));
      return StatusCode.OK;
    }
    if (command.updateIsDefault(update)) {
      return fixedValues.defaultValue(table, column);
    }
    fixedValues.set(command.updateValue(update));
    return StatusCode.OK;
  }

  private StatusCode coerceMutation(
      long value, int sourceDescriptor, int targetDescriptor) {
    return fixedValues.coerce(value, sourceDescriptor, targetDescriptor);
  }

  private StatusCode evaluateMutation(
      int expression,
      long primaryKey,
      HeapRowResult source,
      BoundSqlStatement bound) {
    StatusCode status = rowExpressions.evaluateMutation(
        expression, primaryKey, source);
    if (status.isOk()) {
      mutationNull = rowExpressions.resultNull();
      mutationValue = rowExpressions.resultValue();
      mutationDescriptor = rowExpressions.resultDescriptor();
    }
    return status;
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
