package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Validates names and reachability across adjacent derived projection blocks. */
final class SqlDerivedReferenceValidator {
  private final SqlQuery query;

  SqlDerivedReferenceValidator(SqlQuery ownedQuery) {
    query = ownedQuery;
  }

  StatusCode validate(int blockIndex, boolean allowUnusedComputed) {
    SqlCommand block = query.block(blockIndex);
    SqlCommand inner = blockIndex + 1 < query.blockCount()
        ? query.block(blockIndex + 1) : null;
    if (!validProjectionReferences(block, inner)
        || !validPredicates(block, inner)
        || !validOrder(block, inner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return !allowUnusedComputed
            && blockIndex > 0
            && hasUnreachableComputed(blockIndex)
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }

  private boolean hasUnreachableComputed(int blockIndex) {
    SqlCommand block = query.block(blockIndex);
    SqlCommand outer = query.block(blockIndex - 1);
    for (int projection = 0; projection < block.columnCount(); projection++) {
      SqlScalarExpression expression = block.projectionExpression(projection);
      if (expression != null && expression.isAvailable()
          && !expression.isDirectColumnReference()
          && !expression.isNullLiteral()
          && !references(outer, block.columnOutputName(projection))) {
        return true;
      }
    }
    return false;
  }

  private static boolean references(SqlCommand outer, CharSequence output) {
    for (int projection = 0; projection < outer.columnCount(); projection++) {
      SqlScalarExpression expression = outer.projectionExpression(projection);
      if (references(outer, expression, output)) return true;
    }
    for (int predicate = 0; predicate < outer.predicateCount(); predicate++) {
      SqlScalarExpression expression = outer.predicateExpression(predicate);
      if (expression != null && references(outer, expression, output)
          || expression == null
              && SqlDerivedColumnResolver.sameName(
                  outer.predicateColumnName(predicate), output)) {
        return true;
      }
    }
    return false;
  }

  private static boolean references(
      SqlCommand command,
      SqlScalarExpression expression,
      CharSequence output) {
    for (int node = 0; node < expression.nodeCount(); node++) {
      if (expression.operator(node) != SqlScalarExpression.COLUMN) continue;
      SqlIdentifier name = command.projectionSymbolName(
          (int) expression.operand(node));
      if (name != null && SqlDerivedColumnResolver.sameName(name, output)) {
        return true;
      }
    }
    return false;
  }

  private static boolean validProjectionReferences(
      SqlCommand block, SqlCommand inner) {
    for (int projection = 0; projection < block.columnCount(); projection++) {
      SqlScalarExpression expression = block.projectionExpression(projection);
      if (!validExpressionReferences(block, inner, expression)) return false;
    }
    return true;
  }

  private static boolean validPredicates(SqlCommand block, SqlCommand inner) {
    for (int predicate = 0; predicate < block.predicateCount(); predicate++) {
      SqlScalarExpression expression = block.predicateExpression(predicate);
      if (expression != null) {
        if (!validExpressionReferences(block, inner, expression)) return false;
        continue;
      }
      if (!SqlDerivedColumnResolver.validQualifier(
              block.predicateTableName(predicate), block)
          || block.isColumnPredicate(predicate)
              && !SqlDerivedColumnResolver.validQualifier(
                  block.predicateValueTableName(predicate), block)
          || inner != null
              && !outputContains(inner, block.predicateColumnName(predicate))) {
        return false;
      }
    }
    return true;
  }

  private static boolean validExpressionReferences(
      SqlCommand block,
      SqlCommand inner,
      SqlScalarExpression expression) {
    if (expression == null || !expression.isAvailable()) return false;
    for (int node = 0; node < expression.nodeCount(); node++) {
      if (expression.operator(node) != SqlScalarExpression.COLUMN) continue;
      int symbol = (int) expression.operand(node);
      SqlIdentifier table = block.projectionSymbolTable(symbol);
      SqlIdentifier name = block.projectionSymbolName(symbol);
      if (table == null || name == null
          || !SqlDerivedColumnResolver.validQualifier(table, block)
          || inner != null && !outputContains(inner, name)) {
        return false;
      }
    }
    return true;
  }

  private static boolean validOrder(SqlCommand block, SqlCommand inner) {
    return inner == null || !block.isOrdered()
        || outputContains(inner, block.orderColumnName())
        || SqlDerivedColumnResolver.outputIndex(
            block, block.orderColumnName()) >= 0;
  }

  private static boolean outputContains(SqlCommand command, CharSequence name) {
    return SqlDerivedColumnResolver.outputIndex(command, name) >= 0;
  }
}
