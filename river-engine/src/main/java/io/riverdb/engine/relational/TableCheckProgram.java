package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlValueDomain;

/** Canonical validation rules for persisted deterministic CHECK programs. */
final class TableCheckProgram {
  private static final int INVALID = -1;
  private static final int OWNER = 1 << 30;
  private static final int SIZE_MASK = OWNER - 1;

  private TableCheckProgram() {
  }

  static boolean valid(
      int owner,
      int ownerDescriptor,
      int valueDescriptor,
      int nodes,
      byte[] operators,
      long[] operands,
      int[] descriptors,
      int[] stack) {
    if (operators == null || operands == null || descriptors == null
        || stack == null || nodes > stack.length
        || nodes > operators.length || nodes > operands.length
        || nodes > descriptors.length) {
      return false;
    }
    int state = 0;
    for (int node = 0; node < nodes; node++) {
      int operator = Byte.toUnsignedInt(operators[node]);
      int descriptor = descriptors[node];
      state = step(
          operator, operands[node], descriptor, owner, ownerDescriptor, state, stack);
      if (state == INVALID) return false;
    }
    return validFinal(state, stack, valueDescriptor);
  }

  static int step(
      int operator,
      long operand,
      int descriptor,
      int owner,
      int ownerDescriptor,
      int state,
      int[] stack) {
    return switch (operator) {
      case TableSchema.CHECK_LITERAL -> literal(operand, descriptor, state, stack);
      case TableSchema.CHECK_COLUMN ->
          column(operand, descriptor, owner, ownerDescriptor, state, stack);
      case TableSchema.CHECK_ADD, TableSchema.CHECK_SUBTRACT ->
          binary(operator, operand, descriptor, state, stack);
      case TableSchema.CHECK_CAST, TableSchema.CHECK_EXTRACT ->
          unary(operator, operand, descriptor, state, stack);
      default -> INVALID;
    };
  }

  static boolean validFinal(int state, int[] stack, int valueDescriptor) {
    return state != INVALID
        && (state & OWNER) != 0
        && (state & SIZE_MASK) == 1
        && SqlTypeDescriptor.canCompare(stack[0], valueDescriptor);
  }

  private static int literal(long value, int descriptor, int state, int[] stack) {
    if (!SqlValueDomain.validFixed(descriptor, value)) return INVALID;
    int size = state & SIZE_MASK;
    stack[size] = descriptor;
    return state + 1;
  }

  private static int column(
      long operand,
      int descriptor,
      int owner,
      int ownerDescriptor,
      int state,
      int[] stack) {
    if (operand != owner || descriptor != ownerDescriptor) return INVALID;
    int size = state & SIZE_MASK;
    stack[size] = descriptor;
    return OWNER | state + 1;
  }

  private static int binary(
      int operator, long operand, int descriptor, int state, int[] stack) {
    int size = state & SIZE_MASK;
    if (size < 2 || operand != 0) return INVALID;
    int expected = binaryDescriptor(operator, stack[size - 2], stack[size - 1]);
    if (expected == 0 || descriptor != expected) return INVALID;
    stack[size - 2] = descriptor;
    return (state & OWNER) | size - 1;
  }

  private static int unary(
      int operator, long operand, int descriptor, int state, int[] stack) {
    int size = state & SIZE_MASK;
    if (size < 1 || operator == TableSchema.CHECK_CAST && operand != 0) {
      return INVALID;
    }
    int expected = operator == TableSchema.CHECK_CAST
        ? TableCheckTemporalTypes.castDescriptor(stack[size - 1], descriptor)
        : TableCheckTemporalTypes.extractDescriptor(stack[size - 1], operand);
    if (expected == 0 || descriptor != expected) return INVALID;
    stack[size - 1] = descriptor;
    return state;
  }

  static int binaryDescriptor(int operator, int left, int right) {
    if (SqlTypeDescriptor.typeId(left) != SqlTypeDescriptor.TYPE_ID_DATE) return 0;
    int rightType = SqlTypeDescriptor.typeId(right);
    if (SqlNumericTypeRules.isIntegral(right)) return SqlTypeDescriptor.DATE;
    return operator == TableSchema.CHECK_SUBTRACT
            && rightType == SqlTypeDescriptor.TYPE_ID_DATE
        ? SqlTypeDescriptor.BIGINT : 0;
  }

}
