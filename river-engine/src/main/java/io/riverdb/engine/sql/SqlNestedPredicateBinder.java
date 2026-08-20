package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;

/** Resolves the compact raw predicate plan used by nested execution. */
final class SqlNestedPredicateBinder {
  StatusCode bind(
      BoundSqlQuery query,
      BoundSqlQuery.Block block,
      TableDefinition definition,
      int depth) {
    SqlNestedPredicatePlan predicates = block.predicates();
    for (int predicate = 0; predicate < predicates.count(); predicate++) {
      StatusCode status = bindLeaf(
          query, block, predicates, definition, depth, predicate);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode bindLeaf(
      BoundSqlQuery query,
      BoundSqlQuery.Block block,
      SqlNestedPredicatePlan predicates,
      TableDefinition definition,
      int depth,
      int predicate) {
    if (!validQualifier(block, predicates.tableName(predicate))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = definition.findColumn(predicates.columnName(predicate));
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!predicates.isColumnValue(predicate)) {
      return bindLiteral(query, block, predicates, definition, predicate, column);
    }
    return bindColumn(
        query, block, predicates, definition, depth, predicate, column);
  }

  private static StatusCode bindLiteral(
      BoundSqlQuery query,
      BoundSqlQuery.Block block,
      SqlNestedPredicatePlan predicates,
      TableDefinition definition,
      int predicate,
      int column) {
    boolean dynamic = predicate == query.membershipPredicate(block.blockIndex())
        || predicate == query.scalarPredicate(block.blockIndex());
    int columnDescriptor = definition.typeDescriptor(column);
    if (predicates.isTruth(predicate)) {
      if (SqlTypeDescriptor.typeId(columnDescriptor)
          != SqlTypeDescriptor.TYPE_ID_BOOLEAN) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      block.setPredicate(predicate, column, -1, -1);
      return StatusCode.OK;
    }
    if (!predicates.isNullTest(predicate) && !dynamic) {
      StatusCode status = predicates.isBetween(predicate)
          ? validateBetween(predicates, predicate, columnDescriptor)
          : predicates.isMembership(predicate)
          ? validateMembers(predicates, predicate, columnDescriptor)
          : validateLiteral(predicates, predicate, columnDescriptor);
      if (!status.isOk()) return status;
    }
    block.setPredicate(predicate, column, -1, -1);
    return StatusCode.OK;
  }

  private static StatusCode validateLiteral(
      SqlNestedPredicatePlan predicates, int predicate, int columnDescriptor) {
    int descriptor = predicates.typeDescriptor(predicate);
    return descriptor == 0 && predicates.isValueNull(predicate)
        || SqlTypeDescriptor.canCompare(columnDescriptor, descriptor)
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  private static StatusCode validateMembers(
      SqlNestedPredicatePlan predicates, int predicate, int columnDescriptor) {
    for (int member = 0; member < predicates.memberCount(predicate); member++) {
      int descriptor = predicates.memberDescriptor(predicate, member);
      if (descriptor != 0
          && !SqlTypeDescriptor.canCompare(columnDescriptor, descriptor)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode validateBetween(
      SqlNestedPredicatePlan predicates, int predicate, int columnDescriptor) {
    if (SqlTypeDescriptor.typeId(columnDescriptor)
        == SqlTypeDescriptor.TYPE_ID_BOOLEAN) return StatusCode.DATATYPE_MISMATCH;
    int lower = predicates.lowerDescriptor(predicate);
    int upper = predicates.upperDescriptor(predicate);
    if (lower != 0 && !SqlTypeDescriptor.canCompare(columnDescriptor, lower)
        || upper != 0 && !SqlTypeDescriptor.canCompare(columnDescriptor, upper)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }

  private static StatusCode bindColumn(
      BoundSqlQuery query,
      BoundSqlQuery.Block block,
      SqlNestedPredicatePlan predicates,
      TableDefinition definition,
      int depth,
      int predicate,
      int column) {
    int scope = resolveScope(query, depth, predicates.valueTableName(predicate));
    if (scope < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDefinition valueDefinition = query.block(scope).table();
    int valueColumn = valueDefinition.findColumn(predicates.valueColumnName(predicate));
    if (valueColumn < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!SqlTypeDescriptor.canCompare(
        definition.typeDescriptor(column), valueDefinition.typeDescriptor(valueColumn))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    block.setPredicate(predicate, column, valueColumn, scope);
    return StatusCode.OK;
  }

  private static int resolveScope(
      BoundSqlQuery query, int depth, CharSequence qualifier) {
    if (qualifier.length() == 0) return -1;
    for (int scope = depth; scope >= 0; scope--) {
      if (validQualifier(query.block(scope), qualifier)) return scope;
    }
    return -1;
  }

  private static boolean validQualifier(
      BoundSqlQuery.Block block, CharSequence qualifier) {
    return qualifier.length() == 0
        || SqlBindingNames.same(qualifier, block.tableName())
        || block.tableAlias().length() > 0
            && SqlBindingNames.same(qualifier, block.tableAlias());
  }
}
