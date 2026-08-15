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

  StatusCode copyPredicate(
      int blockIndex,
      SqlScalarExpression source,
      SqlCommand destination) {
    if (destination.hasComputedPredicate()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    SqlScalarExpression target = destination.writablePredicateExpression();
    StatusCode status = appendExpression(blockIndex, source, destination, target);
    if (status.isOk()) finish(source, target);
    return status;
  }

  StatusCode copyPredicateReference(
      int blockIndex,
      CharSequence name,
      SqlCommand destination) {
    if (destination.hasComputedPredicate()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int innerIndex = blockIndex + 1;
    if (innerIndex >= query.blockCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlCommand inner = query.block(innerIndex);
    int projection = SqlDerivedColumnResolver.outputIndex(inner, name);
    if (projection < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    SqlScalarExpression source = inner.projectionExpression(projection);
    SqlScalarExpression target = destination.writablePredicateExpression();
    StatusCode status = appendExpression(innerIndex, source, destination, target);
    if (status.isOk()) finish(source, target);
    return status;
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
    if (blockIndex + 1 < query.blockCount()) {
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
    long operand = source.operand(node);
    if (hasTextOperand(operator, descriptor)) {
      operand = destination.copyTextFrom(sourceCommand, operand);
      if (operand == SqlCommand.INVALID_TEXT_HANDLE) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return target.append(operator, operand, descriptor)
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
