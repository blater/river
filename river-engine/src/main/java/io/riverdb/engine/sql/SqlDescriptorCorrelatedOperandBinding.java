package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves one correlated predicate operand into retained primitive binding lanes. */
final class SqlDescriptorCorrelatedOperandBinding {
  private SqlDescriptorCorrelatedOperandBinding() { }

  static StatusCode bind(
      SqlDescriptorCorrelatedBindingStorage storage,
      SqlCommand command,
      TableDescriptor child,
      SqlCommand outerCommand,
      TableDescriptor outer,
      SqlBooleanPredicateProgram program,
      int leaf,
      int side,
      boolean left) {
    if (program.programNodeCount(leaf, side) != 1) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int operator = program.programOperator(leaf, side, 0);
    long operandHigh = program.programOperandHigh(leaf, side, 0);
    long operand = program.programOperand(leaf, side, 0);
    byte kind;
    int column = -1;
    int descriptor = program.programDescriptor(leaf, side, 0);
    if (operator == SqlScalarExpression.LITERAL) {
      kind = SqlDescriptorCorrelatedBindings.LITERAL;
    } else if (operator == SqlScalarExpression.NULL) {
      kind = SqlDescriptorCorrelatedBindings.NULL;
    } else if (operator == SqlScalarExpression.COLUMN) {
      int symbol = (int) operand;
      CharSequence qualifier = command.predicateSymbolTable(symbol);
      CharSequence name = command.predicateSymbolName(symbol);
      if (qualifier.length() == 0) {
        column = child.findColumn(name);
        if (column >= 0) {
          kind = SqlDescriptorCorrelatedBindings.CHILD;
          descriptor = child.typeDescriptorAt(column);
        } else {
          column = outer.findColumn(name);
          if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
          kind = SqlDescriptorCorrelatedBindings.OUTER;
          descriptor = outer.typeDescriptorAt(column);
        }
      } else if (matches(qualifier, command)) {
        kind = SqlDescriptorCorrelatedBindings.CHILD;
        column = child.findColumn(name);
        if (column >= 0) descriptor = child.typeDescriptorAt(column);
      } else if (matches(qualifier, outerCommand)) {
        kind = SqlDescriptorCorrelatedBindings.OUTER;
        column = outer.findColumn(name);
        if (column >= 0) descriptor = outer.typeDescriptorAt(column);
      } else return StatusCode.INVALID_EXTERNAL_INPUT;
      if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    } else return StatusCode.FEATURE_NOT_SUPPORTED;
    publish(
        storage, leaf, left, kind, column, descriptor, operandHigh, operand);
    return StatusCode.OK;
  }

  private static void publish(
      SqlDescriptorCorrelatedBindingStorage storage,
      int leaf,
      boolean left,
      byte kind,
      int column,
      int descriptor,
      long operandHigh,
      long operand) {
    if (left) {
      storage.leftKinds[leaf] = kind;
      storage.leftColumns[leaf] = column;
      storage.leftDescriptors[leaf] = descriptor;
      storage.leftHighs[leaf] = operandHigh;
      storage.leftValues[leaf] = operand;
    } else {
      storage.rightKinds[leaf] = kind;
      storage.rightColumns[leaf] = column;
      storage.rightDescriptors[leaf] = descriptor;
      storage.rightHighs[leaf] = operandHigh;
      storage.rightValues[leaf] = operand;
    }
  }

  private static boolean matches(CharSequence qualifier, SqlCommand command) {
    return SqlDescriptorPrimaryPredicate.same(qualifier, command.tableName())
        || command.tableAlias().length() > 0
            && SqlDescriptorPrimaryPredicate.same(qualifier, command.tableAlias());
  }
}
