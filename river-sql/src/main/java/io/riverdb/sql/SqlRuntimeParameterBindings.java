package io.riverdb.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.util.Arrays;

/** Session-owned primitive invocation values materialized into one execution frame. */
public final class SqlRuntimeParameterBindings {
  private int[] descriptors = new int[0];
  private long[] highs = new long[0];
  private long[] values = new long[0];
  private int[] textOffsets = new int[0];
  private int[] textLengths = new int[0];
  private boolean[] nulls = new boolean[0];
  private boolean[] consumed = new boolean[0];
  private byte[] text = new byte[0];
  private long[] commandTextHandles = new long[0];
  private int count;
  private int textBytes;

  public StatusCode begin(int parameters, int encodedTextBytes) {
    reset();
    if (parameters < 0 || parameters > SqlShapeLimits.MAX_PARAMETERS
        || encodedTextBytes < 0 || encodedTextBytes > SqlShapeLimits.MAX_ENCODED_PARAMETER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      if (parameters > descriptors.length) {
        int capacity = BoundedArrayGrowth.capacity(
            descriptors.length, parameters, SqlShapeLimits.MAX_PARAMETERS, 8);
        descriptors = Arrays.copyOf(descriptors, capacity);
        highs = Arrays.copyOf(highs, capacity);
        values = Arrays.copyOf(values, capacity);
        textOffsets = Arrays.copyOf(textOffsets, capacity);
        textLengths = Arrays.copyOf(textLengths, capacity);
        nulls = Arrays.copyOf(nulls, capacity);
        consumed = Arrays.copyOf(consumed, capacity);
        commandTextHandles = Arrays.copyOf(commandTextHandles, capacity);
      }
      if (encodedTextBytes > text.length) {
        int capacity = BoundedArrayGrowth.capacity(
            text.length, encodedTextBytes, SqlShapeLimits.MAX_ENCODED_PARAMETER_BYTES, 64);
        text = Arrays.copyOf(text, capacity);
      }
      count = parameters;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public StatusCode set(
      int index, int descriptor, long high, long value, boolean nullValue,
      int encodedTextLength) {
    if (index < 0 || index >= count || descriptor != 0 && !SqlTypeDescriptor.isValid(descriptor)
        || encodedTextLength < 0 || encodedTextLength > text.length - textBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    descriptors[index] = descriptor;
    highs[index] = high;
    values[index] = value;
    nulls[index] = nullValue;
    textOffsets[index] = textBytes;
    textLengths[index] = encodedTextLength;
    textBytes += encodedTextLength;
    return StatusCode.OK;
  }

  public StatusCode setTextByte(int parameter, int index, byte value) {
    if (parameter < 0 || parameter >= count || index < 0 || index >= textLengths[parameter]) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    text[textOffsets[parameter] + index] = value;
    return StatusCode.OK;
  }

  public StatusCode materialize(SqlQuery query, SqlCommand command) {
    if (query == null || command == null || !command.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = materializeCommand(command);
    for (int block = 0; status.isOk() && block < query.blockCount(); block++) {
      if (query.block(block) != command) status = materializeCommand(query.block(block));
    }
    for (int parameter = 0; status.isOk() && parameter < count; parameter++) {
      if (!consumed[parameter]) status = StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return status;
  }

  private StatusCode materializeCommand(SqlCommand command) {
    Arrays.fill(commandTextHandles, 0, count, 0);
    StatusCode status = materialize(command, command.projections);
    if (status.isOk()) status = materializeMutationParameters(command);
    if (status.isOk()) status = materialize(command, command.mutationExpressions);
    if (status.isOk()) status = materialize(command, command.wherePredicates);
    for (int group = 0; status.isOk() && group < command.grouping.count(); group++) {
      status = materialize(command, command.grouping.expression(group));
    }
    if (status.isOk()) status = materialize(command, command.booleanHavingPredicates);
    SqlJoinChain joins = command.joinChain;
    for (int stage = 0; status.isOk() && joins != null && stage < joins.stageCount(); stage++) {
      status = materialize(command, joins.onPredicates(stage));
    }
    return status;
  }

  private StatusCode materializeMutationParameters(SqlCommand command) {
    for (int row = 0; row < command.insertRowCount; row++) {
      for (int column = 0; column < command.insertColumnCount; column++) {
        if (!command.insertHasParameter(row, column)) continue;
        int parameter = (int) command.insertValue(row, column);
        StatusCode status = validate(parameter);
        if (!status.isOk()) return status;
        consumed[parameter] = true;
        long parameterValue = nulls[parameter] ? 0 : value(command, parameter);
        if (invalidTextHandle(parameter, parameterValue)) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        command.inserts.setLiteral(
            row, column, highs[parameter], parameterValue,
            nulls[parameter], descriptors[parameter]);
      }
    }
    if (command.insertRowCount > 0) SqlCommandInsertView.setInsert(command);
    for (int update = 0; update < command.updateColumnCount; update++) {
      if (!command.updateHasParameter(update)) continue;
      int parameter = (int) command.updateValue(update);
      StatusCode status = validate(parameter);
      if (!status.isOk()) return status;
      consumed[parameter] = true;
      long parameterValue = nulls[parameter] ? 0 : value(command, parameter);
      if (invalidTextHandle(parameter, parameterValue)) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      SqlCommandUpdateView.setLiteral(
          command, update, highs[parameter], parameterValue,
          nulls[parameter], descriptors[parameter]);
    }
    return StatusCode.OK;
  }

  public void reset() {
    Arrays.fill(descriptors, 0, count, 0);
    Arrays.fill(highs, 0, count, 0);
    Arrays.fill(values, 0, count, 0);
    Arrays.fill(textOffsets, 0, count, 0);
    Arrays.fill(textLengths, 0, count, 0);
    Arrays.fill(nulls, 0, count, false);
    Arrays.fill(consumed, 0, count, false);
    Arrays.fill(commandTextHandles, 0, count, 0);
    Arrays.fill(text, 0, textBytes, (byte) 0);
    count = 0;
    textBytes = 0;
  }

  private StatusCode materialize(SqlCommand command, SqlProjectionList programs) {
    for (int program = 0; program < command.columnCount; program++) {
      StatusCode status = materialize(command, programs.expression(program));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode materialize(SqlCommand command, SqlMutationExpressions programs) {
    for (int node = 0; node < programs.nodeCount; node++) {
      if (Byte.toUnsignedInt(programs.operators[node]) != SqlScalarExpression.PARAMETER) continue;
      StatusCode status = resolve(
          command, (int) programs.operands[node], programs.operators,
          programs.operandHighs, programs.operands, programs.descriptors, node);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode materialize(SqlCommand command, SqlBooleanPredicateProgram program) {
    for (int node = 0; node < program.scalarNodeCount; node++) {
      if (Byte.toUnsignedInt(program.scalarOperators[node]) != SqlScalarExpression.PARAMETER) {
        continue;
      }
      StatusCode status = resolve(
          command, (int) program.scalarOperands[node], program.scalarOperators,
          program.scalarOperandHighs, program.scalarOperands, program.scalarDescriptors, node);
      if (!status.isOk()) return status;
    }
    for (int member = 0; member < program.memberCount; member++) {
      if (Byte.toUnsignedInt(program.memberKinds[member]) != SqlScalarExpression.PARAMETER) continue;
      int parameter = (int) program.memberValues[member];
      StatusCode status = validate(parameter);
      if (!status.isOk()) return status;
      consumed[parameter] = true;
      program.memberKinds[member] = (byte) (nulls[parameter]
          ? SqlScalarExpression.NULL : SqlScalarExpression.LITERAL);
      program.memberNulls[member] = nulls[parameter];
      program.memberDescriptors[member] = descriptors[parameter];
      program.memberHighs[member] = nulls[parameter] ? 0 : highs[parameter];
      if (nulls[parameter]) {
        program.memberValues[member] = 0;
      } else {
        long value = value(command, parameter);
        if (invalidTextHandle(parameter, value)) return StatusCode.RESOURCE_EXHAUSTED;
        program.memberValues[member] = value;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode materialize(SqlCommand command, SqlScalarExpression expression) {
    for (int node = 0; node < expression.nodeCount; node++) {
      if (Byte.toUnsignedInt(expression.operators[node]) != SqlScalarExpression.PARAMETER) continue;
      StatusCode status = resolve(
          command, (int) expression.operands[node], expression.operators,
          expression.operandHighs, expression.operands, expression.typeDescriptors, node);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode resolve(
      SqlCommand command, int parameter, byte[] operators, long[] operandHighs,
      long[] operands, int[] types, int node) {
    StatusCode status = validate(parameter);
    if (!status.isOk()) return status;
    consumed[parameter] = true;
    operators[node] = (byte) (nulls[parameter]
        ? SqlScalarExpression.NULL : SqlScalarExpression.LITERAL);
    operandHighs[node] = nulls[parameter] ? 0 : highs[parameter];
    if (nulls[parameter]) {
      operands[node] = 0;
    } else {
      long value = value(command, parameter);
      if (invalidTextHandle(parameter, value)) return StatusCode.RESOURCE_EXHAUSTED;
      operands[node] = value;
    }
    types[node] = descriptors[parameter];
    return StatusCode.OK;
  }

  private StatusCode validate(int parameter) {
    return parameter >= 0 && parameter < count
        ? StatusCode.OK : StatusCode.PARAMETER_COUNT_MISMATCH;
  }

  private boolean invalidTextHandle(int parameter, long value) {
    return SqlTypeDescriptor.typeId(descriptors[parameter])
        == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && value == SqlCommand.INVALID_TEXT_HANDLE;
  }

  private long value(SqlCommand command, int parameter) {
    if (SqlTypeDescriptor.typeId(descriptors[parameter]) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return values[parameter];
    }
    if (commandTextHandles[parameter] != 0) return commandTextHandles[parameter];
    long handle = SqlCommandTextStore.store(
        command, text, textOffsets[parameter], textLengths[parameter]);
    commandTextHandles[parameter] = handle;
    return handle;
  }
}
