package io.riverdb.engine.api;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Scalar type rules used while editing postfix expressions. */
final class TransactionProgramTypeRules {
  private TransactionProgramTypeRules() { }

  static boolean accepts(int operator, int first, int second, int third, int target) {
    int targetType = SqlTypeDescriptor.typeId(target);
    return switch (operator) {
      case TransactionScalarOperator.ADD, TransactionScalarOperator.SUBTRACT,
          TransactionScalarOperator.MULTIPLY, TransactionScalarOperator.DIVIDE,
          TransactionScalarOperator.REMAINDER -> numeric(first, second, target);
      case TransactionScalarOperator.EQUAL, TransactionScalarOperator.NOT_EQUAL,
          TransactionScalarOperator.LESS, TransactionScalarOperator.LESS_OR_EQUAL,
          TransactionScalarOperator.GREATER, TransactionScalarOperator.GREATER_OR_EQUAL ->
          SqlTypeDescriptor.canCompare(first, second) && target == SqlTypeDescriptor.BOOLEAN;
      case TransactionScalarOperator.AND, TransactionScalarOperator.OR ->
          first == SqlTypeDescriptor.BOOLEAN && second == SqlTypeDescriptor.BOOLEAN
              && target == SqlTypeDescriptor.BOOLEAN;
      case TransactionScalarOperator.NOT -> first == SqlTypeDescriptor.BOOLEAN
          && target == SqlTypeDescriptor.BOOLEAN;
      case TransactionScalarOperator.SELECT -> first == SqlTypeDescriptor.BOOLEAN
          && SqlTypeDescriptor.canExplicitlyCast(second, target)
          && SqlTypeDescriptor.canExplicitlyCast(third, target);
      case TransactionScalarOperator.CAST -> SqlTypeDescriptor.canExplicitlyCast(first, target);
      case TransactionScalarOperator.CONCAT -> SqlTypeDescriptor.typeId(first)
              == SqlTypeDescriptor.TYPE_ID_VARCHAR
          && SqlTypeDescriptor.typeId(second) == SqlTypeDescriptor.TYPE_ID_VARCHAR
          && targetType == SqlTypeDescriptor.TYPE_ID_VARCHAR;
      default -> false;
    };
  }

  private static boolean numeric(int first, int second, int target) {
    return SqlNumericTypeRules.isNumeric(first)
        && SqlNumericTypeRules.isNumeric(second)
        && SqlNumericTypeRules.isNumeric(target);
  }
}
