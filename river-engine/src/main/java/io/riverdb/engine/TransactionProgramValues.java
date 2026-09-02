package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.api.TransactionScalarOperator;
import io.riverdb.engine.sql.SqlProgramMemoryLease;
import io.riverdb.engine.sql.SqlRetainedBudget;

/** Shared typed-value evaluation and copying for every transaction-program step shape. */
final class TransactionProgramValues {
  private final ParameterSet parameters;
  private final TransactionProgramArguments dataflow;
  private final TransactionScalarEvaluator evaluator;
  private final TransactionTextSource text = new TransactionTextSource();
  private StatusCode status = StatusCode.OK;

  TransactionProgramValues(SqlRetainedBudget budget) {
    parameters = new ParameterSet(
        ParameterSet.MAXIMUM_PARAMETERS,
        ParameterSet.MAXIMUM_TEXT_BYTES,
        new SqlProgramMemoryLease(budget));
    dataflow = new TransactionProgramArguments(new SqlProgramMemoryLease(budget));
    evaluator = new TransactionScalarEvaluator(new SqlProgramMemoryLease(budget));
  }

  void reset() { dataflow.reset(); }
  ParameterSet parameters() { return parameters; }
  StatusCode status() { return status; }

  int guardTarget(
      TransactionProgram program, TransactionProgramArguments arguments, int step) {
    int expression = program.guardExpression(step);
    if (expression < 0) return -1;
    status = evaluator.evaluate(program, expression, arguments, dataflow);
    if (!status.isOk()) return Integer.MIN_VALUE;
    if (evaluator.descriptor() != SqlTypeDescriptor.BOOLEAN) {
      status = StatusCode.INVARIANT_BROKEN;
      return Integer.MIN_VALUE;
    }
    return evaluator.isNull() || evaluator.low() == 0 ? program.falseTarget(step) : -1;
  }

  StatusCode bind(
      TransactionProgram program, TransactionProgramArguments arguments, int step) {
    parameters.reset();
    int first = program.firstParameter(step);
    int end = first + program.parameterCount(step);
    for (int parameter = first; parameter < end; parameter++) {
      StatusCode evaluated = evaluator.evaluate(
          program, program.parameterExpression(parameter), arguments, dataflow);
      if (!evaluated.isOk()) return evaluated;
      evaluated = appendParameter();
      if (!evaluated.isOk()) return evaluated;
    }
    return StatusCode.OK;
  }

  StatusCode captureDataflow(
      TransactionProgram program, int step, TransactionValueReader source, int columns) {
    for (int node = program.referenceHead(step); node >= 0; node = program.referenceNext(node)) {
      int column = program.nodeSecond(node);
      if (column >= columns || source.descriptor(column) != program.nodeDescriptor(node)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      StatusCode copied = copyToDataflow(node, source, column);
      if (!copied.isOk()) return copied;
    }
    return StatusCode.OK;
  }

  StatusCode captureOutput(
      TransactionProgram program,
      int step,
      TransactionValueReader source,
      int columns,
      TransactionProgramResult result) {
    int count = program.captureCount(step);
    StatusCode copied = result.beginRow(count);
    int first = program.firstCapture(step);
    for (int index = 0; copied.isOk() && index < count; index++) {
      int column = program.captureColumnAt(first + index);
      if (column < 0 || column >= columns) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      copied = appendOutput(source, column, result);
    }
    return copied;
  }

  StatusCode validateArguments(
      TransactionProgram program, TransactionProgramArguments arguments) {
    for (int node = 0; node < program.nodeCount(); node++) {
      if (program.nodeOperator(node) != TransactionScalarOperator.ARGUMENT) continue;
      int slot = program.nodeFirst(node);
      if (!arguments.isSet(slot)) return StatusCode.PARAMETER_COUNT_MISMATCH;
      if (arguments.typeDescriptorAt(slot) != program.nodeDescriptor(node)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
    }
    return StatusCode.OK;
  }

  StatusCode close() {
    StatusCode closed = parameters.release();
    StatusCode next = dataflow.release();
    if (closed.isOk()) closed = next;
    next = evaluator.release();
    return closed.isOk() ? next : closed;
  }

  private StatusCode appendParameter() {
    int descriptor = evaluator.descriptor();
    if (evaluator.isNull()) return parameters.appendNull(descriptor);
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      text.pointTo(evaluator.reader(), 0);
      return parameters.appendText(descriptor, text);
    }
    return SqlTypeDescriptor.isWideDecimal(descriptor)
        ? parameters.appendDecimal128(
            SqlTypeDescriptor.parameterOne(descriptor),
            SqlTypeDescriptor.parameterTwo(descriptor), evaluator.high(), evaluator.low())
        : parameters.appendFixed(descriptor, evaluator.low());
  }

  private StatusCode copyToDataflow(int slot, TransactionValueReader source, int column) {
    int descriptor = source.descriptor(column);
    if (source.isNull(column)) return dataflow.setNull(slot, descriptor);
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      text.pointTo(source, column);
      return dataflow.setText(slot, descriptor, text);
    }
    return SqlTypeDescriptor.isWideDecimal(descriptor)
        ? dataflow.setDecimal128(slot, descriptor, source.high(column), source.low(column))
        : dataflow.setFixed(slot, descriptor, source.low(column));
  }

  private StatusCode appendOutput(
      TransactionValueReader source, int column, TransactionProgramResult result) {
    int descriptor = source.descriptor(column);
    if (source.isNull(column)) return result.appendNull(descriptor);
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      text.pointTo(source, column);
      return result.appendText(descriptor, text);
    }
    return SqlTypeDescriptor.isWideDecimal(descriptor)
        ? result.appendDecimal128(descriptor, source.high(column), source.low(column))
        : result.appendFixed(descriptor, source.high(column), source.low(column));
  }
}
