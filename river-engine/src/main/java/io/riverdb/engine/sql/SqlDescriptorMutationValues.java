package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import java.nio.ByteBuffer;

/** Reusable descriptor-shaped input, fetched row, and scalar-copy state. */
final class SqlDescriptorMutationValues {
  private static final ByteBuffer EMPTY_TEXT_BYTES = ByteBuffer.allocate(0);
  private static final char[] EMPTY_TEXT_CHARACTERS = new char[0];
  private final SqlValueBuffer fetched = new SqlValueBuffer();
  private final SqlValueBuffer mutation = new SqlValueBuffer();
  private ByteBuffer commandText = EMPTY_TEXT_BYTES;
  private char[] textChars = EMPTY_TEXT_CHARACTERS;
  private final SqlDescriptorNumericAssignment numeric =
      new SqlDescriptorNumericAssignment();

  StatusCode reserve(TableDescriptor table) {
    fetched.reset();
    mutation.reset();
    int textBytes = maximumTextBytes(table);
    if (textBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = fetched.reserve(
        table.columnCount(), table.columnCount(), textBytes, textBytes);
    return status.isOk() ? mutation.reserve(
        table.columnCount(), table.columnCount(), textBytes, textBytes) : status;
  }

  SqlValueBuffer fetched() { return fetched; }
  SqlValueBuffer mutation() { return mutation; }

  StatusCode buildInsert(
      SqlCommand command, TableDescriptor table,
      SqlDescriptorColumnMapping columns,
      SqlRowProjectionEvaluator expressions,
      int row) {
    StatusCode status = mutation.clearForSize(table.columnCount());
    for (int column = 0; status.isOk() && column < table.columnCount(); column++) {
      int source = columns.sourceAt(column);
      status = source < 0
          ? missingInsertValue(table, column)
          : command.insertHasExpression(row, source)
              ? assignInsertExpression(
                  command, expressions, row, source, column, table)
              : assign(command, row, source, column, table, true);
    }
    return status;
  }

  StatusCode buildInsert(
      SqlCommand command, TableDescriptor table,
      SqlDescriptorColumnMapping columns, int row) {
    return buildInsert(command, table, columns, null, row);
  }

  StatusCode buildUpdate(
      SqlCommand command, TableDescriptor table, SqlDescriptorColumnMapping columns,
      SqlRowProjectionEvaluator expressions) {
    StatusCode status = mutation.clearForSize(table.columnCount());
    for (int column = 0; status.isOk() && column < table.columnCount(); column++) {
      int source = columns.sourceAt(column);
      status = source < 0
          ? copyFetched(column, table.typeDescriptorAt(column))
          : command.updateHasExpression(source)
              ? assignExpression(command, source, column, table, expressions)
              : assign(command, 0, source, column, table, false);
    }
    return status;
  }

  private StatusCode assignExpression(
      SqlCommand command, int source, int column, TableDescriptor table,
      SqlRowProjectionEvaluator expressions) {
    if (expressions == null) return StatusCode.CONFLICT;
    StatusCode status = expressions.evaluateDescriptorMutation(
        command.updateExpression(source), fetched);
    if (!status.isOk()) return status;
    return assignEvaluated(expressions, column, table);
  }

  private StatusCode assignEvaluated(
      SqlRowProjectionEvaluator expressions, int column, TableDescriptor table) {
    int supplied = expressions.resultDescriptor();
    int target = table.typeDescriptorAt(column);
    if (expressions.resultNull()) {
      if (!SqlTypedNullAssignment.compatible(supplied, target)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      return table.isNullable(column)
          ? mutation.setNull(column, target) : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!SqlTypeDescriptor.canImplicitlyCast(supplied, target)
        && !SqlNumericTypeRules.canAssign(supplied, target)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return SqlNumericTypeRules.isNumeric(target)
        ? numeric.assign(
            mutation, column,
            expressions.resultHighValue(), expressions.resultValue(),
            supplied, target)
        : SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_VARCHAR
            ? StatusCode.FEATURE_NOT_SUPPORTED
            : mutation.setFixed(column, target, expressions.resultValue());
  }

  private StatusCode assign(
      SqlCommand command, int row, int source, int column,
      TableDescriptor table, boolean insert) {
    boolean isNull = insert ? command.insertIsNull(row, source) : command.updateIsNull(source);
    boolean isDefault = insert
        ? command.insertIsDefault(row, source) : command.updateIsDefault(source);
    if (isDefault) return setDefault(table, column);
    int target = table.typeDescriptorAt(column);
    int supplied = insert
        ? command.insertTypeDescriptor(row, source) : command.updateTypeDescriptor(source);
    if (isNull) {
      if (!SqlTypedNullAssignment.compatible(supplied, target)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      return table.isNullable(column)
          ? mutation.setNull(column, target) : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!SqlTypeDescriptor.canImplicitlyCast(supplied, target)
        && !SqlNumericTypeRules.canAssign(supplied, target)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    long high = insert
        ? command.insertValueHigh(row, source) : command.updateValueHigh(source);
    long value = insert ? command.insertValue(row, source) : command.updateValue(source);
    if (SqlNumericTypeRules.isNumeric(target)) {
      return numeric.assign(mutation, column, high, value, supplied, target);
    }
    if (SqlTypeDescriptor.typeId(target) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return mutation.setFixed(column, target, value);
    }
    int required = command.textByteLength(value);
    StatusCode capacity = reserveCommandText(required);
    if (!capacity.isOk()) return capacity;
    commandText.clear();
    int bytes = command.copyText(value, commandText);
    return bytes < 0 ? StatusCode.INVALID_EXTERNAL_INPUT
        : mutation.setTextBytes(column, target, commandText, 0, bytes);
  }

  private StatusCode assignInsertExpression(
      SqlCommand command,
      SqlRowProjectionEvaluator expressions,
      int row,
      int source,
      int column,
      TableDescriptor table) {
    if (expressions == null) return StatusCode.CONFLICT;
    StatusCode status = expressions.evaluateMutation(
        command.insertExpression(row, source), 0, null);
    if (!status.isOk()) return status;
    return assignEvaluated(expressions, column, table);
  }

  private StatusCode missingInsertValue(TableDescriptor table, int column) {
    if (table.columns().defaultKindAt(column) != 0) return setDefault(table, column);
    return table.isNullable(column)
        ? mutation.setNull(column, table.typeDescriptorAt(column))
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode setDefault(TableDescriptor table, int column) {
    int descriptor = table.typeDescriptorAt(column);
    if (table.columns().defaultKindAt(column) == 0) {
      return table.isNullable(column)
          ? mutation.setNull(column, descriptor) : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return SqlTypeDescriptor.isWideDecimal(descriptor)
        ? mutation.setDecimal128(
            column, descriptor, table.columns().defaultHighAt(column),
            table.columns().defaultValueAt(column))
        : mutation.setFixed(column, descriptor, table.columns().defaultValueAt(column));
  }

  private StatusCode copyFetched(int column, int descriptor) {
    if (fetched.isNull(column)) return mutation.setNull(column, descriptor);
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      return mutation.setDecimal128(
          column, descriptor, fetched.highValueAt(column), fetched.valueAt(column));
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return mutation.setFixed(column, descriptor, fetched.valueAt(column));
    }
    StatusCode capacity = reserveTextCharacters(fetched.textByteLengthAt(column));
    if (!capacity.isOk()) return capacity;
    int chars = fetched.copyTextChars(column, textChars, 0);
    return chars < 0 ? StatusCode.CORRUPTION
        : mutation.setText(column, descriptor, textChars, 0, chars);
  }

  private static int maximumTextBytes(TableDescriptor table) {
    long bytes = 0;
    for (int index = 0; index < table.columnCount(); index++) {
      int descriptor = table.typeDescriptorAt(index);
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += SqlTypeDescriptor.parameterOne(descriptor) * 4L;
        if (bytes > TableSchema.MAXIMUM_ROW_BYTES) return -1;
      }
    }
    return (int) bytes;
  }

  private StatusCode reserveCommandText(int required) {
    if (required < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (required <= commandText.capacity()) return StatusCode.OK;
    try {
      commandText = ByteBuffer.allocate(required);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserveTextCharacters(int required) {
    if (required < 0) return StatusCode.CORRUPTION;
    if (required <= textChars.length) return StatusCode.OK;
    try {
      textChars = new char[required];
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

}
