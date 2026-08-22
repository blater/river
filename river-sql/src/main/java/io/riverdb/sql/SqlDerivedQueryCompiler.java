package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Flattens a validated derived-table block chain into one executable command. */
final class SqlDerivedQueryCompiler {
  private final SqlQuery query;
  private final SqlDerivedColumnResolver columns;
  private final SqlDerivedProjectionCompiler projections;
  private final SqlDerivedPredicateCompiler predicates;
  private final SqlDerivedBlockValidator blocks;

  SqlDerivedQueryCompiler(SqlQuery ownedQuery) {
    query = ownedQuery;
    columns = new SqlDerivedColumnResolver(ownedQuery);
    projections = new SqlDerivedProjectionCompiler(ownedQuery);
    predicates = new SqlDerivedPredicateCompiler(ownedQuery, columns, projections);
    blocks = new SqlDerivedBlockValidator(ownedQuery);
  }

  StatusCode compile(SqlCommand destination) {
    return compile(destination, false);
  }

  StatusCode compile(SqlCommand destination, boolean allowUnusedComputed) {
    StatusCode status = blocks.validate(allowUnusedComputed);
    if (!status.isOk()) return status;
    SqlCommand root = query.block(0);
    SqlCommand base = query.block(query.sourceBlockCount() - 1);
    destination.writableTableName().copyFrom(base.tableName());
    status = projections.copy(root, destination);
    if (status.isOk()) status = predicates.copy(destination);
    if (status.isOk()) status = predicates.copyOrder(root, destination);
    if (!status.isOk()) return status;
    destination.setRowLimit(root.rowLimit());
    if (root.type() == SqlCommandType.SELECT) {
      destination.set(SqlCommandType.SELECT, destination.key(), 0);
    } else {
      destination.setScan(0, 0, false);
    }
    return destination.finish();
  }

  StatusCode compilePipeline(SqlCommand destination) {
    StatusCode status = blocks.validatePipeline();
    if (!status.isOk()) return status;
    SqlCommand root = query.block(0);
    SqlCommand base = query.block(query.sourceBlockCount() - 1);
    destination.copyQueryFrom(root);
    destination.writableTableName().copyFrom(base.tableName());
    return destination.finish();
  }

  StatusCode validatePipeline(int firstBlock) {
    return blocks.validatePipeline(firstBlock);
  }
}
