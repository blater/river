package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Flattens a validated derived-table block chain into one executable command. */
final class SqlDerivedQueryCompiler {
  private final SqlQuery query;

  SqlDerivedQueryCompiler(SqlQuery ownedQuery) {
    query = ownedQuery;
  }

  StatusCode compile(SqlCommand destination) {
    StatusCode status = validateBlocks();
    if (!status.isOk()) {
      return status;
    }
    SqlCommand root = query.block(0);
    SqlCommand base = query.block(query.blockCount() - 1);
    destination.writableTableName().copyFrom(base.tableName());
    status = copyProjections(root, destination);
    if (status.isOk()) {
      status = copyPredicates(destination);
    }
    if (status.isOk()) {
      status = copyOrder(root, destination);
    }
    if (!status.isOk()) {
      return status;
    }
    destination.setRowLimit(root.rowLimit());
    destination.setScan(0, 0, false);
    return destination.finish();
  }

  private StatusCode validateBlocks() {
    if (query.blockCount() < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < query.blockCount(); index++) {
      SqlCommand block = query.block(index);
      if (!validBlockShape(block, index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = index + 1 < query.blockCount()
          ? validateOuterBlock(block, query.block(index + 1))
          : validateBaseBlock(block);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private static boolean validBlockShape(SqlCommand block, int index) {
    return (block.type() == SqlCommandType.SCAN || block.type() == SqlCommandType.SELECT)
        && !block.hasDisjunction()
        && !block.isSelectAll()
        && block.columnCount() > 0
        && (index == 0 || block.rowLimit() == Long.MAX_VALUE);
  }

  private StatusCode copyProjections(
      SqlCommand root,
      SqlCommand destination) {
    for (int index = 0; index < root.columnCount(); index++) {
      SqlIdentifier column = destination.writableNextColumnName();
      if (column == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      int resolvedNull = root.isNullProjection(index)
          ? 1 : copyResolvedColumn(0, root.columnName(index), column);
      if (resolvedNull < 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (resolvedNull > 0) {
        column.copyFrom(root.columnName(index));
        destination.markLastProjectionNull();
      }
      destination.writableColumnAlias(index).copyFrom(root.columnOutputName(index));
    }
    return StatusCode.OK;
  }

  private StatusCode copyPredicates(SqlCommand destination) {
    for (int blockIndex = query.blockCount() - 1; blockIndex >= 0; blockIndex--) {
      SqlCommand block = query.block(blockIndex);
      for (int predicate = 0; predicate < block.predicateCount(); predicate++) {
        StatusCode status = copyPredicate(blockIndex, block, predicate, destination);
        if (!status.isOk()) {
          return status;
        }
      }
    }
    return StatusCode.OK;
  }

  private StatusCode copyPredicate(
      int blockIndex,
      SqlCommand block,
      int predicate,
      SqlCommand destination) {
    SqlIdentifier column = destination.writableNextPredicateColumnName();
    if (column == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (copyResolvedColumn(
        blockIndex, block.predicateColumnName(predicate), column) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (block.isNullPredicate(predicate)) {
      destination.appendNullPredicate(block.isNullPredicateNegated(predicate));
    } else if (block.isColumnPredicate(predicate)) {
      destination.writableNextPredicateValueTableName().copyFrom(
          block.predicateValueTableName(predicate));
      destination.writableNextPredicateValueColumnName().copyFrom(
          block.predicateValueColumnName(predicate));
      destination.appendColumnPredicate();
    } else if (block.isRangePredicate(predicate)) {
      destination.appendPredicate(
          block.predicateValue(predicate),
          block.predicateLowerInclusive(predicate),
          block.predicateUpperExclusive(predicate),
          false);
    } else {
      destination.appendComparison(block.predicateValue(predicate), block.comparison(predicate));
    }
    return StatusCode.OK;
  }

  private StatusCode copyOrder(
      SqlCommand root,
      SqlCommand destination) {
    if (!root.isOrdered()) {
      return StatusCode.OK;
    }
    int projection = outputIndex(root, root.orderColumnName());
    CharSequence ordered = projection >= 0
        ? root.columnName(projection) : root.orderColumnName();
    if (copyResolvedColumn(0, ordered, destination.writableOrderColumnName()) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.setDescendingOrder(root.isDescendingOrder());
    return StatusCode.OK;
  }

  private static StatusCode validateOuterBlock(SqlCommand outer, SqlCommand inner) {
    for (int index = 0; index < outer.columnCount(); index++) {
      if (!validQualifier(outer.columnTableName(index), outer)
          || !outer.isNullProjection(index)
              && !outputContains(inner, outer.columnName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < outer.predicateCount(); index++) {
      if (!validQualifier(outer.predicateTableName(index), outer)
          || !outputContains(inner, outer.predicateColumnName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    if (outer.isOrdered()
        && !outputContains(inner, outer.orderColumnName())
        && outputIndex(outer, outer.orderColumnName()) < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  private static StatusCode validateBaseBlock(SqlCommand base) {
    for (int index = 0; index < base.columnCount(); index++) {
      if (!validQualifier(base.columnTableName(index), base)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < base.predicateCount(); index++) {
      if (!validQualifier(base.predicateTableName(index), base)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }

  private static boolean outputContains(SqlCommand command, CharSequence name) {
    return outputIndex(command, name) >= 0;
  }

  private static int outputIndex(SqlCommand command, CharSequence name) {
    int found = -1;
    for (int index = 0; index < command.columnCount(); index++) {
      if (sameName(command.columnOutputName(index), name)) {
        if (found >= 0) {
          return -2;
        }
        found = index;
      }
    }
    return found;
  }

  private int copyResolvedColumn(
      int blockIndex,
      CharSequence name,
      SqlIdentifier destination) {
    CharSequence resolved = name;
    for (int sourceIndex = blockIndex + 1;
        sourceIndex < query.blockCount();
        sourceIndex++) {
      SqlCommand source = query.block(sourceIndex);
      int projection = outputIndex(source, resolved);
      if (projection < 0) {
        return -1;
      }
      if (source.isNullProjection(projection)) {
        return 1;
      }
      resolved = source.columnName(projection);
    }
    destination.copyFrom(resolved);
    return 0;
  }

  private static boolean validQualifier(CharSequence qualifier, SqlCommand command) {
    return qualifier.length() == 0
        || sameName(qualifier, command.tableName())
        || command.tableAlias().length() > 0
            && sameName(qualifier, command.tableAlias());
  }

  private static boolean sameName(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
  }
}
