package io.riverdb.engine.api;

/** Stable typed postfix operators used by reusable transaction programs. */
public final class TransactionScalarOperator {
  public static final int ARGUMENT = 1;
  public static final int RESULT = 2;
  public static final int NULL = 3;
  public static final int ADD = 4;
  public static final int SUBTRACT = 5;
  public static final int MULTIPLY = 6;
  public static final int DIVIDE = 7;
  public static final int REMAINDER = 8;
  public static final int EQUAL = 9;
  public static final int NOT_EQUAL = 10;
  public static final int LESS = 11;
  public static final int LESS_OR_EQUAL = 12;
  public static final int GREATER = 13;
  public static final int GREATER_OR_EQUAL = 14;
  public static final int AND = 15;
  public static final int OR = 16;
  public static final int NOT = 17;
  public static final int SELECT = 18;
  public static final int CAST = 19;
  public static final int CONCAT = 20;

  private TransactionScalarOperator() { }

  public static boolean isValid(int operator) { return operands(operator) >= 0; }

  static boolean leaf(int operator) {
    return operator >= ARGUMENT && operator <= NULL;
  }

  static int operands(int operator) {
    return switch (operator) {
      case ARGUMENT, RESULT, NULL -> 0;
      case NOT, CAST -> 1;
      case SELECT -> 3;
      case ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER,
          EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL,
          AND, OR, CONCAT -> 2;
      default -> -1;
    };
  }
}
