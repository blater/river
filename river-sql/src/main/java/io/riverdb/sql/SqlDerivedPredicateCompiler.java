package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Rehomes derived predicates and selected ordering onto the base command. */
final class SqlDerivedPredicateCompiler {
  private final SqlQuery query;
  private final SqlDerivedColumnResolver columns;
  private final SqlDerivedPredicateValues values = new SqlDerivedPredicateValues();
  private final SqlDerivedComputedPredicateCompiler computed;

  SqlDerivedPredicateCompiler(
      SqlQuery ownedQuery,
      SqlDerivedColumnResolver columnResolver,
      SqlDerivedProjectionCompiler projectionCompiler) {
    query = ownedQuery;
    columns = columnResolver;
    computed = new SqlDerivedComputedPredicateCompiler(projectionCompiler, values);
  }

  StatusCode copy(SqlCommand destination) {
    for (int block = query.blockCount() - 1; block >= 0; block--) {
      SqlCommand source = query.block(block);
      for (int predicate = 0; predicate < source.predicateCount(); predicate++) {
        StatusCode status = copyOne(block, source, predicate, destination);
        if (!status.isOk()) return status;
      }
    }
    return StatusCode.OK;
  }

  StatusCode copyOrder(SqlCommand root, SqlCommand destination) {
    if (!root.isOrdered()) return StatusCode.OK;
    int projection = SqlDerivedColumnResolver.outputIndex(
        root, root.orderColumnName());
    if (projection >= 0
        && !destination.projectionExpression(projection).isDirectColumnReference()) {
      destination.writableOrderColumnName().copyFrom(root.orderColumnName());
    } else {
      CharSequence ordered = projection >= 0
          ? root.columnName(projection) : root.orderColumnName();
      int resolved = columns.copy(
          0, ordered, destination.writableOrderColumnName());
      if (resolved != 0) return resolutionStatus(resolved);
    }
    destination.setDescendingOrder(root.isDescendingOrder());
    return StatusCode.OK;
  }

  private StatusCode copyOne(
      int block, SqlCommand source, int predicate, SqlCommand destination) {
    SqlIdentifier column = destination.writableNextPredicateColumnName();
    if (column == null) return StatusCode.RESOURCE_EXHAUSTED;
    if (source.predicateExpression(predicate) != null) {
      return computed.copy(block, source, predicate, destination);
    }
    int resolved = columns.copy(
        block, source.predicateColumnName(predicate), column);
    if (resolved != 0) {
      return block == 0 && resolved > 0
          ? computed.promote(source, predicate, destination)
          : resolutionStatus(resolved);
    }
    if (source.isNullPredicate(predicate)) {
      return values.copy(source, predicate, destination);
    }
    if (source.isColumnPredicate(predicate)) {
      return copyColumn(block, source, predicate, destination);
    }
    return values.copy(source, predicate, destination);
  }

  private StatusCode copyColumn(
      int block, SqlCommand source, int predicate, SqlCommand destination) {
    int resolved = columns.copy(
        block,
        source.predicateValueColumnName(predicate),
        destination.writableNextPredicateValueColumnName());
    if (resolved != 0) return resolutionStatus(resolved);
    destination.appendColumnPredicate();
    return StatusCode.OK;
  }

  private static StatusCode resolutionStatus(int resolved) {
    return resolved > 0
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
