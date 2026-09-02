package io.riverdb.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.error.StatusCode;

/** Fixed-capacity postfix program for one scalar exact-value expression. */
public final class SqlScalarExpression {
  public static final int MAXIMUM_NODES = SqlShapeLimits.MAX_EXPRESSION_NODES;

  public static final int LITERAL = 1;
  public static final int NEGATE = 2;
  public static final int ADD = 3;
  public static final int SUBTRACT = 4;
  public static final int MULTIPLY = 5;
  public static final int DIVIDE = 6;
  public static final int REMAINDER = 7;
  public static final int ABSOLUTE = 8;
  public static final int CEILING = 9;
  public static final int FLOOR = 10;
  public static final int ROUND = 11;
  public static final int TRUNCATE = 12;
  public static final int CAST = 13;
  public static final int CURRENT_DATE = 14;
  public static final int CURRENT_TIMESTAMP = 15;
  public static final int LOCALTIME = 16;
  public static final int LOCALTIMESTAMP = 17;
  public static final int AT_TIME_ZONE = 18;
  public static final int EXTRACT = 19;
  public static final int COLUMN = 20;
  public static final int NULL = 21;
  public static final int AGGREGATE_VALUE = 22;
  public static final int GROUP_VALUE = 23;
  public static final int PARAMETER = 24;

  byte[] operators = new byte[16];
  long[] operandHighs = new long[16];
  long[] operands = new long[16];
  int[] typeDescriptors = new int[16];
  int nodeCount;
  private int resultTypeDescriptor;
  private boolean available;

  void reset() {
    for (int index = 0; index < nodeCount; index++) {
      operators[index] = 0;
      operandHighs[index] = 0;
      operands[index] = 0;
      typeDescriptors[index] = 0;
    }
    nodeCount = 0;
    resultTypeDescriptor = 0;
    available = false;
  }

  boolean append(int operator, long operand, int typeDescriptor) {
    return append(operator, operand >> 63, operand, typeDescriptor);
  }

  boolean append(
      int operator, long operandHigh, long operand, int typeDescriptor) {
    if (nodeCount >= MAXIMUM_NODES
        || !SqlScalarCapacity.ensure(this, nodeCount + 1)) {
      return false;
    }
    operators[nodeCount] = (byte) operator;
    operandHighs[nodeCount] = operandHigh;
    operands[nodeCount] = operand;
    typeDescriptors[nodeCount] = typeDescriptor;
    nodeCount++;
    return true;
  }

  void finish(int descriptor) {
    resultTypeDescriptor = descriptor;
    available = nodeCount > 0 && descriptor != 0;
  }

  void finishUnresolved() {
    resultTypeDescriptor = 0;
    available = nodeCount > 0;
  }

  StatusCode copyFrom(SqlScalarExpression source) {
    reset();
    if (source == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < source.nodeCount; index++) {
      if (!append(
          source.operators[index],
          source.operandHighs[index],
          source.operands[index],
          source.typeDescriptors[index])) {
        reset();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    resultTypeDescriptor = source.resultTypeDescriptor;
    available = source.available;
    return StatusCode.OK;
  }

  public boolean isAvailable() {
    return available;
  }

  public int nodeCount() {
    return nodeCount;
  }

  public int operator(int index) {
    return index >= 0 && index < nodeCount ? Byte.toUnsignedInt(operators[index]) : 0;
  }

  public long operand(int index) {
    return index >= 0 && index < nodeCount ? operands[index] : 0;
  }

  public long operandHigh(int index) {
    return index >= 0 && index < nodeCount ? operandHighs[index] : 0;
  }

  public int typeDescriptor(int index) {
    return index >= 0 && index < nodeCount ? typeDescriptors[index] : 0;
  }

  public int resultTypeDescriptor() {
    return available ? resultTypeDescriptor : 0;
  }

  public boolean isDirectColumnReference() {
    return available && nodeCount == 1
        && Byte.toUnsignedInt(operators[0]) == COLUMN;
  }

  public boolean isNullLiteral() {
    return available && nodeCount == 1
        && Byte.toUnsignedInt(operators[0]) == NULL;
  }

  public boolean hasColumnReference() {
    for (int index = 0; index < nodeCount; index++) {
      if (Byte.toUnsignedInt(operators[index]) == COLUMN) return true;
    }
    return false;
  }

  public void replaceWithLiteral(long value, int descriptor) {
    reset();
    append(LITERAL, value, descriptor);
    finish(descriptor);
  }

  public void replaceWithDecimalLiteral(
      long high, long low, int descriptor) {
    reset();
    append(LITERAL, high, low, descriptor);
    finish(descriptor);
  }

  public boolean replaceNodeWithLiteral(int index, long value, int descriptor) {
    if (index < 0 || index >= nodeCount || !SqlTypeDescriptor.isValid(descriptor)) {
      return false;
    }
    operators[index] = LITERAL;
    operands[index] = value;
    typeDescriptors[index] = descriptor;
    return true;
  }
}
