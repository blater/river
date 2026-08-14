package io.riverdb.sql;

/** Fixed-capacity postfix program for one scalar exact-value expression. */
public final class SqlScalarExpression {
  public static final int MAXIMUM_NODES = 32;

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

  private final byte[] operators = new byte[MAXIMUM_NODES];
  private final long[] operands = new long[MAXIMUM_NODES];
  private final int[] typeDescriptors = new int[MAXIMUM_NODES];
  private int nodeCount;
  private int resultTypeDescriptor;
  private boolean available;

  void reset() {
    for (int index = 0; index < nodeCount; index++) {
      operators[index] = 0;
      operands[index] = 0;
      typeDescriptors[index] = 0;
    }
    nodeCount = 0;
    resultTypeDescriptor = 0;
    available = false;
  }

  boolean append(int operator, long operand, int typeDescriptor) {
    if (nodeCount >= operators.length) {
      return false;
    }
    operators[nodeCount] = (byte) operator;
    operands[nodeCount] = operand;
    typeDescriptors[nodeCount] = typeDescriptor;
    nodeCount++;
    return true;
  }

  void finish(int descriptor) {
    resultTypeDescriptor = descriptor;
    available = nodeCount > 0 && descriptor != 0;
  }

  void copyFrom(SqlScalarExpression source) {
    reset();
    if (source == null) {
      return;
    }
    for (int index = 0; index < source.nodeCount; index++) {
      append(
          source.operators[index],
          source.operands[index],
          source.typeDescriptors[index]);
    }
    resultTypeDescriptor = source.resultTypeDescriptor;
    available = source.available;
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

  public int typeDescriptor(int index) {
    return index >= 0 && index < nodeCount ? typeDescriptors[index] : 0;
  }

  public int resultTypeDescriptor() {
    return available ? resultTypeDescriptor : 0;
  }
}
