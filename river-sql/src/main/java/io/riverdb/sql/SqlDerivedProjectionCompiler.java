package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Composes bounded derived projection programs onto the physical root table. */
final class SqlDerivedProjectionCompiler {
  private final SqlQuery query;

  SqlDerivedProjectionCompiler(SqlQuery ownedQuery) {
    query = ownedQuery;
  }

  StatusCode copy(SqlCommand root, SqlCommand destination) {
    for (int projection = 0; projection < root.columnCount(); projection++) {
      SqlIdentifier column = destination.writableNextColumnName();
      if (column == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      column.copyFrom(root.columnName(projection));
      SqlScalarExpression target =
          destination.writableProjectionExpression(projection);
      StatusCode status = appendExpression(
          0, root.projectionExpression(projection), destination, target);
      if (!status.isOk()) {
        return status;
      }
      finish(root.projectionExpression(projection), target);
      if (target.isDirectColumnReference()) {
        destination.adoptDirectProjectionName(projection);
      } else if (target.isNullLiteral()) {
        destination.markLastProjectionNull();
      }
      destination.writableColumnAlias(projection).copyFrom(
          root.columnOutputName(projection));
    }
    return StatusCode.OK;
  }

  StatusCode copyPredicateProgram(
      int blockIndex,
      SqlBooleanPredicateProgram source,
      int leaf,
      int program,
      SqlCommand destination,
      SqlScalarExpression target) {
    target.reset();
    SqlCommand block = query.block(blockIndex);
    int count = source.programNodeCount(leaf, program);
    if (count == 0) return StatusCode.OK;
    for (int node = 0; node < count; node++) {
      int operator = source.programOperator(leaf, program, node);
      StatusCode status = operator == SqlScalarExpression.COLUMN
          ? appendPredicateColumn(
              blockIndex,
              block,
              (int) source.programOperand(leaf, program, node),
              source.programDescriptor(leaf, program, node),
              destination,
              target)
          : appendPredicateNode(
              block,
              source,
              leaf,
              program,
              node,
              destination,
              target);
      if (!status.isOk()) return status;
    }
    int descriptor = source.programDescriptor(leaf, program, count - 1);
    if (descriptor == 0) target.finishUnresolved();
    else target.finish(descriptor);
    return StatusCode.OK;
  }

  private StatusCode appendPredicateColumn(
      int blockIndex,
      SqlCommand block,
      int symbol,
      int descriptor,
      SqlCommand destination,
      SqlScalarExpression target) {
    SqlIdentifier table = block.projectionSymbolTable(symbol);
    SqlIdentifier name = block.projectionSymbolName(symbol);
    if (table == null || name == null
        || !SqlDerivedColumnResolver.validQualifier(table, block)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (blockIndex + 1 < query.sourceBlockCount()) {
      SqlCommand inner = query.block(blockIndex + 1);
      int projection = SqlDerivedColumnResolver.outputIndex(inner, name);
      return projection < 0 ? StatusCode.INVALID_EXTERNAL_INPUT
          : appendExpression(
              blockIndex + 1,
              inner.projectionExpression(projection),
              destination,
              target);
    }
    int targetSymbol = destination.registerPredicateSymbol("", name);
    return targetSymbol >= 0
            && target.append(SqlScalarExpression.COLUMN, targetSymbol, descriptor)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private static StatusCode appendPredicateNode(
      SqlCommand sourceCommand,
      SqlBooleanPredicateProgram source,
      int leaf,
      int program,
      int node,
      SqlCommand destination,
      SqlScalarExpression target) {
    int operator = source.programOperator(leaf, program, node);
    int descriptor = source.programDescriptor(leaf, program, node);
    long operandHigh = source.programOperandHigh(leaf, program, node);
    long operand = source.programOperand(leaf, program, node);
    if (hasTextOperand(operator, descriptor)) {
      operand = destination.copyTextFrom(sourceCommand, operand);
      operandHigh = 0;
      if (operand == SqlCommand.INVALID_TEXT_HANDLE) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return target.append(operator, operandHigh, operand, descriptor)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private StatusCode appendExpression(
      int blockIndex,
      SqlScalarExpression source,
      SqlCommand destination,
      SqlScalarExpression target) {
    if (source == null || !source.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlCommand block = query.block(blockIndex);
    for (int node = 0; node < source.nodeCount(); node++) {
      int operator = source.operator(node);
      StatusCode status = operator == SqlScalarExpression.COLUMN
          ? appendColumn(blockIndex, block, source, node, destination, target)
          : appendNode(block, source, node, destination, target);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode appendColumn(
      int blockIndex,
      SqlCommand block,
      SqlScalarExpression expression,
      int node,
      SqlCommand destination,
      SqlScalarExpression target) {
    int symbol = (int) expression.operand(node);
    SqlIdentifier table = block.projectionSymbolTable(symbol);
    SqlIdentifier name = block.projectionSymbolName(symbol);
    if (table == null || name == null
        || !SqlDerivedColumnResolver.validQualifier(table, block)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (blockIndex + 1 < query.sourceBlockCount()) {
      SqlCommand inner = query.block(blockIndex + 1);
      int projection = SqlDerivedColumnResolver.outputIndex(inner, name);
      return projection < 0
          ? StatusCode.INVALID_EXTERNAL_INPUT
          : appendExpression(
              blockIndex + 1,
              inner.projectionExpression(projection),
              destination,
              target);
    }
    int targetSymbol = destination.registerProjectionSymbol("", name);
    return targetSymbol >= 0
            && target.append(
                SqlScalarExpression.COLUMN,
                targetSymbol,
                expression.typeDescriptor(node))
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private static StatusCode appendNode(
      SqlCommand sourceCommand,
      SqlScalarExpression source,
      int node,
      SqlCommand destination,
      SqlScalarExpression target) {
    int operator = source.operator(node);
    int descriptor = source.typeDescriptor(node);
    long operandHigh = source.operandHigh(node);
    long operand = source.operand(node);
    if (hasTextOperand(operator, descriptor)) {
      operand = destination.copyTextFrom(sourceCommand, operand);
      operandHigh = 0;
      if (operand == SqlCommand.INVALID_TEXT_HANDLE) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return target.append(operator, operandHigh, operand, descriptor)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private static boolean hasTextOperand(int operator, int descriptor) {
    return operator == SqlScalarExpression.AT_TIME_ZONE
        || operator == SqlScalarExpression.LITERAL
            && SqlTypeDescriptor.typeId(descriptor)
                == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private static void finish(
      SqlScalarExpression source, SqlScalarExpression destination) {
    if (source.resultTypeDescriptor() == 0) {
      destination.finishUnresolved();
    } else {
      destination.finish(source.resultTypeDescriptor());
    }
  }

  static boolean hasComputedProjection(SqlCommand block) {
    for (int projection = 0; projection < block.columnCount(); projection++) {
      SqlScalarExpression expression = block.projectionExpression(projection);
      if (expression != null && expression.isAvailable()
          && !expression.isDirectColumnReference()
          && !expression.isNullLiteral()) {
        return true;
      }
    }
    return false;
  }

}
