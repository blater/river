package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal128Arithmetic;
import io.riverdb.base.type.ExactDecimal128Conversion;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionScalarOperator;
import io.riverdb.engine.sql.SqlProgramMemoryLease;

/** Allocation-free evaluator for the typed transaction scalar IR. */
final class TransactionScalarEvaluator {
  private final TransactionScalarStack stack;
  private final TransactionArgumentReader arguments = new TransactionArgumentReader();
  private final TransactionArgumentReader dataflow = new TransactionArgumentReader();
  private final ExactDecimal.LongValue compact = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch compactScratch = new ExactDecimal.WideScratch();
  private final ExactDecimal128.Value wide = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch wideScratch = new ExactDecimal128.Scratch();

  TransactionScalarEvaluator(SqlProgramMemoryLease memory) {
    stack = new TransactionScalarStack(memory);
  }

  StatusCode evaluate(
      TransactionProgram program,
      int expression,
      TransactionProgramArguments invocation,
      TransactionProgramArguments priorResults) {
    stack.reset();
    StatusCode status = stack.prepare(program.maximumStackDepth());
    arguments.pointTo(invocation);
    dataflow.pointTo(priorResults);
    int end = program.expressionFirstNode(expression) + program.expressionNodeCount(expression);
    for (int node = program.expressionFirstNode(expression); status.isOk() && node < end; node++) {
      status = evaluateNode(program, node);
    }
    return status.isOk() && stack.depth() == 1 ? StatusCode.OK
        : status.isOk() ? StatusCode.INVARIANT_BROKEN : status;
  }

  int descriptor() { return stack.descriptor(0); }
  long high() { return stack.high(0); }
  long low() { return stack.low(0); }
  boolean isNull() { return stack.isNull(0); }
  int textLength() { return stack.textLength(0); }
  char textCharacter(int character) { return stack.textCharacter(0, character); }
  TransactionValueReader reader() { return stack; }
  StatusCode release() { return stack.release(); }

  private StatusCode evaluateNode(TransactionProgram program, int node) {
    int operator = program.nodeOperator(node);
    int descriptor = program.nodeDescriptor(node);
    if (operator == TransactionScalarOperator.ARGUMENT) {
      return push(arguments, program.nodeFirst(node), descriptor);
    }
    if (operator == TransactionScalarOperator.RESULT) {
      return push(dataflow, node, descriptor);
    }
    if (operator == TransactionScalarOperator.NULL) return stack.push(descriptor, 0, 0, true);
    if (operator == TransactionScalarOperator.CONCAT) return concatenate(descriptor);
    if (operator == TransactionScalarOperator.SELECT) return select(descriptor);
    if (operator == TransactionScalarOperator.NOT) return not();
    if (operator == TransactionScalarOperator.AND || operator == TransactionScalarOperator.OR) {
      return booleanBinary(operator);
    }
    if (operator >= TransactionScalarOperator.EQUAL
        && operator <= TransactionScalarOperator.GREATER_OR_EQUAL) {
      return compare(operator);
    }
    if (operator == TransactionScalarOperator.CAST) return cast(descriptor);
    return arithmetic(operator, descriptor);
  }

  private StatusCode push(TransactionArgumentReader source, int slot, int expected) {
    if (!source.isSet(slot)) return StatusCode.PARAMETER_COUNT_MISMATCH;
    int actual = source.descriptor(slot);
    if (actual != expected) return StatusCode.DATATYPE_MISMATCH;
    return source.textLength(slot) >= 0 && !source.isNull(slot)
        ? stack.pushText(actual, source, slot)
        : stack.push(actual, source.high(slot), source.low(slot), source.isNull(slot));
  }

  private StatusCode concatenate(int descriptor) {
    int right = stack.depth() - 1;
    int left = right - 1;
    if (!stack.isNull(left) && !stack.isNull(right)) {
      int scalars = scalarCount(stack, left) + scalarCount(stack, right);
      if (scalars < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (scalars > SqlTypeDescriptor.parameterOne(descriptor)) {
        return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
      }
    }
    return stack.concatenate(descriptor);
  }

  private StatusCode select(int descriptor) {
    int conditionSlot = stack.depth() - 3;
    boolean condition = !stack.isNull(conditionSlot) && stack.low(conditionSlot) != 0;
    int chosen = condition ? stack.depth() - 2 : stack.depth() - 1;
    StatusCode status = convert(chosen, descriptor);
    if (status.isOk()) stack.select(condition, descriptor, wide.high, wide.low);
    return status;
  }

  private StatusCode not() {
    int slot = stack.depth() - 1;
    stack.unary(SqlTypeDescriptor.BOOLEAN, 0, stack.low(slot) == 0 ? 1 : 0, stack.isNull(slot));
    return StatusCode.OK;
  }

  private StatusCode booleanBinary(int operator) {
    int right = stack.depth() - 1;
    int left = right - 1;
    boolean identity = operator == TransactionScalarOperator.AND;
    if (!stack.isNull(left) && (stack.low(left) != 0) != identity
        || !stack.isNull(right) && (stack.low(right) != 0) != identity) {
      stack.binary(SqlTypeDescriptor.BOOLEAN, 0, identity ? 0 : 1, false);
    } else if (stack.isNull(left) || stack.isNull(right)) {
      stack.binary(SqlTypeDescriptor.BOOLEAN, 0, 0, true);
    } else {
      stack.binary(SqlTypeDescriptor.BOOLEAN, 0, identity ? 1 : 0, false);
    }
    return StatusCode.OK;
  }

  private StatusCode compare(int operator) {
    int right = stack.depth() - 1;
    int left = right - 1;
    if (stack.isNull(left) || stack.isNull(right)) {
      stack.binary(SqlTypeDescriptor.BOOLEAN, 0, 0, true);
      return StatusCode.OK;
    }
    int compared = compareValues(left, right);
    boolean value = switch (operator) {
      case TransactionScalarOperator.EQUAL -> compared == 0;
      case TransactionScalarOperator.NOT_EQUAL -> compared != 0;
      case TransactionScalarOperator.LESS -> compared < 0;
      case TransactionScalarOperator.LESS_OR_EQUAL -> compared <= 0;
      case TransactionScalarOperator.GREATER -> compared > 0;
      default -> compared >= 0;
    };
    stack.binary(SqlTypeDescriptor.BOOLEAN, 0, value ? 1 : 0, false);
    return StatusCode.OK;
  }

  private int compareValues(int left, int right) {
    int leftDescriptor = stack.descriptor(left);
    int rightDescriptor = stack.descriptor(right);
    if (SqlNumericTypeRules.isNumeric(leftDescriptor)) {
      if (SqlNumericTypeRules.isApproximate(leftDescriptor)
          || SqlNumericTypeRules.isApproximate(rightDescriptor)) {
        return Double.compare(doubleValue(left), doubleValue(right));
      }
      if (SqlTypeDescriptor.isWideDecimal(leftDescriptor)
          || SqlTypeDescriptor.isWideDecimal(rightDescriptor)) {
        return ExactDecimal128.compare(
            normalizedHigh(left), stack.low(left), scale(leftDescriptor),
            normalizedHigh(right), stack.low(right), scale(rightDescriptor), wideScratch);
      }
      return SqlNumericValue.compare(
          stack.low(left), leftDescriptor, stack.low(right), rightDescriptor);
    }
    if (stack.textLength(left) >= 0) return compareText(left, right);
    return Long.compare(stack.low(left), stack.low(right));
  }

  private int compareText(int left, int right) {
    int common = Math.min(stack.textLength(left), stack.textLength(right));
    for (int index = 0; index < common; index++) {
      int compared = Character.compare(stack.textCharacter(left, index), stack.textCharacter(right, index));
      if (compared != 0) return compared;
    }
    return Integer.compare(stack.textLength(left), stack.textLength(right));
  }

  private StatusCode cast(int target) {
    int slot = stack.depth() - 1;
    if (stack.isNull(slot)) {
      stack.unary(target, 0, 0, true);
      return StatusCode.OK;
    }
    StatusCode status = convert(slot, target);
    if (status.isOk()) {
      if (SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        stack.retagTop(target);
      } else {
        stack.unary(target, wide.high, wide.low, false);
      }
    }
    return status;
  }

  private StatusCode arithmetic(int operator, int target) {
    int right = stack.depth() - 1;
    int left = right - 1;
    if (stack.isNull(left) || stack.isNull(right)) {
      stack.binary(target, 0, 0, true);
      return StatusCode.OK;
    }
    int leftDescriptor = stack.descriptor(left);
    int rightDescriptor = stack.descriptor(right);
    StatusCode status;
    if (SqlNumericTypeRules.isApproximate(leftDescriptor)
        || SqlNumericTypeRules.isApproximate(rightDescriptor)
        || SqlNumericTypeRules.isApproximate(target)) {
      return approximateArithmetic(operator, left, right, target);
    }
    if (SqlTypeDescriptor.isWideDecimal(leftDescriptor)
        || SqlTypeDescriptor.isWideDecimal(rightDescriptor)
        || SqlTypeDescriptor.isWideDecimal(target)) {
      status = wideArithmetic(operator, left, right, target);
      if (status.isOk()) stack.binary(target, wide.high, wide.low, false);
      return status;
    }
    status = switch (operator) {
      case TransactionScalarOperator.ADD -> ExactDecimal.add(
          stack.low(left), leftDescriptor, stack.low(right), rightDescriptor,
          false, target, compact, compactScratch);
      case TransactionScalarOperator.SUBTRACT -> ExactDecimal.add(
          stack.low(left), leftDescriptor, stack.low(right), rightDescriptor,
          true, target, compact, compactScratch);
      case TransactionScalarOperator.MULTIPLY -> ExactDecimal.multiply(
          stack.low(left), leftDescriptor, stack.low(right), rightDescriptor,
          target, compact, compactScratch);
      case TransactionScalarOperator.DIVIDE -> ExactDecimal.divide(
          stack.low(left), leftDescriptor, stack.low(right), rightDescriptor,
          target, compact, compactScratch);
      case TransactionScalarOperator.REMAINDER -> ExactDecimal.remainder(
          stack.low(left), leftDescriptor, stack.low(right), rightDescriptor,
          target, compact, compactScratch);
      default -> StatusCode.INVARIANT_BROKEN;
    };
    if (status.isOk()) stack.binary(target, compact.value >> 63, compact.value, false);
    return status;
  }

  private StatusCode wideArithmetic(int operator, int left, int right, int target) {
    int leftDescriptor = stack.descriptor(left);
    int rightDescriptor = stack.descriptor(right);
    int targetPrecision = precision(target);
    int targetScale = scale(target);
    return switch (operator) {
      case TransactionScalarOperator.ADD, TransactionScalarOperator.SUBTRACT -> ExactDecimal128.add(
          normalizedHigh(left), stack.low(left), precision(leftDescriptor), scale(leftDescriptor),
          normalizedHigh(right), stack.low(right), precision(rightDescriptor), scale(rightDescriptor),
          operator == TransactionScalarOperator.SUBTRACT,
          targetPrecision, targetScale, wide, wideScratch);
      case TransactionScalarOperator.MULTIPLY -> ExactDecimal128Arithmetic.multiply(
          normalizedHigh(left), stack.low(left), precision(leftDescriptor), scale(leftDescriptor),
          normalizedHigh(right), stack.low(right), precision(rightDescriptor), scale(rightDescriptor),
          targetPrecision, targetScale, wide, wideScratch);
      case TransactionScalarOperator.DIVIDE -> ExactDecimal128Arithmetic.divide(
          normalizedHigh(left), stack.low(left), precision(leftDescriptor), scale(leftDescriptor),
          normalizedHigh(right), stack.low(right), precision(rightDescriptor), scale(rightDescriptor),
          targetPrecision, targetScale, wide, wideScratch);
      case TransactionScalarOperator.REMAINDER -> ExactDecimal128Arithmetic.remainder(
          normalizedHigh(left), stack.low(left), precision(leftDescriptor), scale(leftDescriptor),
          normalizedHigh(right), stack.low(right), precision(rightDescriptor), scale(rightDescriptor),
          targetPrecision, targetScale, wide, wideScratch);
      default -> StatusCode.INVARIANT_BROKEN;
    };
  }

  private StatusCode approximateArithmetic(int operator, int left, int right, int target) {
    double leftValue = doubleValue(left);
    double rightValue = doubleValue(right);
    if ((operator == TransactionScalarOperator.DIVIDE
        || operator == TransactionScalarOperator.REMAINDER) && rightValue == 0.0d) {
      return StatusCode.DIVISION_BY_ZERO;
    }
    double result = switch (operator) {
      case TransactionScalarOperator.ADD -> leftValue + rightValue;
      case TransactionScalarOperator.SUBTRACT -> leftValue - rightValue;
      case TransactionScalarOperator.MULTIPLY -> leftValue * rightValue;
      case TransactionScalarOperator.DIVIDE -> leftValue / rightValue;
      case TransactionScalarOperator.REMAINDER -> leftValue % rightValue;
      default -> Double.NaN;
    };
    if (!Double.isFinite(result)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    int targetType = SqlTypeDescriptor.typeId(target);
    if (targetType == SqlTypeDescriptor.TYPE_ID_REAL) {
      float converted = (float) result;
      if (!Float.isFinite(converted)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      stack.binary(target, 0, SqlApproximateNumeric.realBits(converted), false);
      return StatusCode.OK;
    }
    if (targetType == SqlTypeDescriptor.TYPE_ID_DOUBLE) {
      stack.binary(target, 0, SqlApproximateNumeric.doubleBits(result), false);
      return StatusCode.OK;
    }
    StatusCode status;
    if (SqlTypeDescriptor.isWideDecimal(target)) {
      status = ExactDecimal128Conversion.fromDouble(
          result, precision(target), scale(target), wide, wideScratch);
    } else {
      status = SqlNumericValue.assign(
          SqlApproximateNumeric.doubleBits(result), SqlTypeDescriptor.DOUBLE,
          target, compact, compactScratch);
      if (status.isOk()) {
        wide.high = compact.value >> Long.SIZE - 1;
        wide.low = compact.value;
      }
    }
    if (status.isOk()) stack.binary(target, wide.high, wide.low, false);
    return status;
  }

  private StatusCode convert(int slot, int target) {
    int source = stack.descriptor(slot);
    if (source == target) {
      wide.high = stack.high(slot);
      wide.low = stack.low(slot);
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(source) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      int scalars = scalarCount(stack, slot);
      if (scalars < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      return scalars <= SqlTypeDescriptor.parameterOne(target)
          ? StatusCode.OK : StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    }
    if (!SqlNumericTypeRules.isNumeric(source) || !SqlNumericTypeRules.isNumeric(target)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (SqlNumericTypeRules.isApproximate(source)
        && SqlTypeDescriptor.isWideDecimal(target)) {
      return ExactDecimal128Conversion.fromDouble(
          doubleValue(slot), precision(target), scale(target), wide, wideScratch);
    }
    if (SqlTypeDescriptor.isWideDecimal(source)
        && SqlNumericTypeRules.isApproximate(target)) {
      double value = ExactDecimal128Conversion.toDouble(
          stack.high(slot), stack.low(slot), scale(source), wideScratch);
      if (!Double.isFinite(value)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      wide.high = 0;
      wide.low = SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_REAL
          ? SqlApproximateNumeric.realBits((float) value)
          : SqlApproximateNumeric.doubleBits(value);
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.isWideDecimal(source) || SqlTypeDescriptor.isWideDecimal(target)) {
      return ExactDecimal128.quantize(
          normalizedHigh(slot), stack.low(slot), precision(source), scale(source),
          precision(target), scale(target), ExactDecimal128.ROUND_HALF_AWAY,
          false, wide, wideScratch);
    }
    StatusCode status = SqlNumericValue.assign(
        stack.low(slot), source, target, compact, compactScratch);
    if (status.isOk()) {
      wide.high = compact.value >> 63;
      wide.low = compact.value;
    }
    return status;
  }

  private long normalizedHigh(int slot) {
    return SqlTypeDescriptor.isWideDecimal(stack.descriptor(slot))
        ? stack.high(slot) : stack.low(slot) >> 63;
  }
  private double doubleValue(int slot) {
    int descriptor = stack.descriptor(slot);
    return SqlTypeDescriptor.isWideDecimal(descriptor)
        ? ExactDecimal128Conversion.toDouble(
            stack.high(slot), stack.low(slot), scale(descriptor), wideScratch)
        : SqlNumericValue.doubleValue(stack.low(slot), descriptor);
  }
  private static int precision(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> 5;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> 10;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> 19;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> SqlTypeDescriptor.parameterOne(descriptor);
      default -> 19;
    };
  }
  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }
  private static int scalarCount(TransactionValueReader source, int slot) {
    int count = 0;
    for (int index = 0; index < source.textLength(slot); index++) {
      char value = source.textCharacter(slot, index);
      if (Character.isHighSurrogate(value)) {
        if (++index >= source.textLength(slot)
            || !Character.isLowSurrogate(source.textCharacter(slot, index))) return -1;
      } else if (Character.isLowSurrogate(value)) return -1;
      count++;
    }
    return count;
  }
}
