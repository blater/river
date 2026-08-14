package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;

/** Resolves captured nested-query blocks into reusable execution indices. */
final class SqlQueryBlockBinder {
  StatusCode bind(RelationalSession session, BoundSqlStatement bound) {
    BoundSqlQuery query = bound.executableQuery;
    query.beginBinding(bound.table);
    for (int depth = 1; depth < query.blockCount(); depth++) {
      StatusCode status = bindBlock(session, query, depth);
      if (!status.isOk()) {
        return status;
      }
    }
    bindTopology(query);
    return StatusCode.OK;
  }

  private StatusCode bindBlock(
      RelationalSession session, BoundSqlQuery query, int depth) {
    BoundSqlQuery.Block block = query.block(depth);
    if (!hasValidShape(block)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TableDefinition definition = block.writableTable();
    StatusCode status = session.resolveTable(block.tableName(), definition);
    if (!status.isOk()) {
      return status;
    }
    status = bindProjection(block, definition);
    if (!status.isOk()) {
      return status;
    }
    status = bindPredicates(query, block, definition, depth);
    return status.isOk()
        ? validateResultEdge(query, depth - 1, block.projectionType())
        : status;
  }

  private static boolean hasValidShape(BoundSqlQuery.Block block) {
    return block != null && !block.isOrdered()
        && block.columnCount() == 1 && !block.isSelectAll();
  }

  private static StatusCode bindProjection(
      BoundSqlQuery.Block block, TableDefinition definition) {
    if (block.columnTableName(0).length() > 0
        && !matchesQualifier(block, block.columnTableName(0))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int projection = block.isNullProjection(0)
        ? BoundSqlStatement.NULL_PROJECTION
        : definition.findColumn(block.firstColumnName());
    if (projection < 0 && projection != BoundSqlStatement.NULL_PROJECTION) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int descriptor = projection == BoundSqlStatement.NULL_PROJECTION
        ? 0 : definition.typeDescriptor(projection);
    block.setProjection(projection, descriptor);
    return StatusCode.OK;
  }

  private StatusCode bindPredicates(
      BoundSqlQuery query,
      BoundSqlQuery.Block block,
      TableDefinition definition,
      int depth) {
    for (int predicate = 0; predicate < block.predicateCount(); predicate++) {
      StatusCode status = bindPredicate(
          query, block, definition, depth, predicate);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode bindPredicate(
      BoundSqlQuery query,
      BoundSqlQuery.Block block,
      TableDefinition definition,
      int depth,
      int predicate) {
    if (!hasValidQualifier(block, predicate)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = definition.findColumn(block.predicateColumnName(predicate));
    if (column < 0 || hasInvalidRange(block, predicate)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!block.isColumnPredicate(predicate)) {
      return bindLiteralPredicate(query, block, definition, predicate, column);
    }
    return bindColumnPredicate(query, block, definition, depth, predicate, column);
  }

  private static boolean hasValidQualifier(
      BoundSqlQuery.Block block, int predicate) {
    CharSequence qualifier = block.predicateTableName(predicate);
    return qualifier.length() == 0 || matchesQualifier(block, qualifier);
  }

  private static boolean hasInvalidRange(
      BoundSqlQuery.Block block, int predicate) {
    return block.isRangePredicate(predicate)
        && block.predicateUpperExclusive(predicate)
            <= block.predicateLowerInclusive(predicate);
  }

  private static StatusCode bindLiteralPredicate(
      BoundSqlQuery query,
      BoundSqlQuery.Block block,
      TableDefinition definition,
      int predicate,
      int column) {
    boolean nestedMembership = predicate == query.membershipPredicate(block.blockIndex());
    if (!block.isNullPredicate(predicate) && !nestedMembership
        && !SqlTypeDescriptor.canCompare(
            definition.typeDescriptor(column),
            block.predicateTypeDescriptor(predicate))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    block.setPredicate(predicate, column, -1, -1);
    return StatusCode.OK;
  }

  private static StatusCode bindColumnPredicate(
      BoundSqlQuery query,
      BoundSqlQuery.Block block,
      TableDefinition definition,
      int depth,
      int predicate,
      int column) {
    int scope = resolveScope(
        query, depth, block.predicateValueTableName(predicate));
    if (scope < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TableDefinition valueDefinition = query.block(scope).table();
    int valueColumn = valueDefinition.findColumn(
        block.predicateValueColumnName(predicate));
    if (valueColumn < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!SqlTypeDescriptor.canCompare(
        definition.typeDescriptor(column),
        valueDefinition.typeDescriptor(valueColumn))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    block.setPredicate(predicate, column, valueColumn, scope);
    return StatusCode.OK;
  }

  private static StatusCode validateResultEdge(
      BoundSqlQuery query, int parent, int childType) {
    boolean scalar = query.hasScalarPredicate(parent);
    boolean membership = query.hasMembershipPredicate(parent);
    if (!scalar && !membership) {
      return StatusCode.OK;
    }
    BoundSqlQuery.Block parentBlock = query.block(parent);
    int predicate = scalar
        ? query.scalarPredicate(parent) : query.membershipPredicate(parent);
    int parentColumn = predicate < 0 ? -1
        : parentBlock.table().findColumn(parentBlock.predicateColumnName(predicate));
    if (parentColumn < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (childType != 0 && !SqlTypeDescriptor.canCompare(
        parentBlock.table().typeDescriptor(parentColumn), childType)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return scalar && isTextType(childType)
        ? StatusCode.DATATYPE_MISMATCH : StatusCode.OK;
  }

  private static void bindTopology(BoundSqlQuery query) {
    int correlation = 0;
    for (int depth = 1; depth < query.blockCount(); depth++) {
      correlation |= correlationFlags(query.block(depth), depth);
    }
    boolean correlated = (correlation & 1) != 0;
    boolean rootCorrelated = (correlation & 2) != 0;
    boolean intermediateCorrelated = (correlation & 4) != 0;
    boolean simple = query.blockCount() == 2;
    query.setCorrelationTopology(
        simple && query.hasScalarPredicate() && correlated,
        simple && query.hasExistencePredicate() && correlated,
        simple && query.hasMembershipPredicate() && correlated,
        !intermediateCorrelated && query.blockCount() > 2 && correlated,
        intermediateCorrelated,
        intermediateCorrelated && rootCorrelated);
  }

  private static int correlationFlags(BoundSqlQuery.Block block, int depth) {
    int flags = block.isCorrelated() ? 1 : 0;
    for (int predicate = 0; predicate < block.predicateCount(); predicate++) {
      int scope = block.resolvedPredicateValueScope(predicate);
      if (scope == 0) {
        flags |= 2;
      } else if (scope > 0 && scope < depth) {
        flags |= 4;
      }
    }
    return flags;
  }

  private static int resolveScope(
      BoundSqlQuery query, int depth, CharSequence qualifier) {
    if (qualifier.length() == 0) {
      return -1;
    }
    for (int scope = depth; scope >= 0; scope--) {
      if (matchesQualifier(query.block(scope), qualifier)) {
        return scope;
      }
    }
    return -1;
  }

  private static boolean isTextType(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor)
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private static boolean matchesQualifier(
      BoundSqlQuery.Block block, CharSequence qualifier) {
    return SqlBindingNames.same(qualifier, block.tableName())
        || block.tableAlias().length() > 0
            && SqlBindingNames.same(qualifier, block.tableAlias());
  }
}
