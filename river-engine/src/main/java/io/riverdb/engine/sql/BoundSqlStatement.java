package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Reusable catalog-resolved state borrowed by one statement execution. */
final class BoundSqlStatement extends SqlBoundAccess {
  static final int NULL_PROJECTION = Integer.MIN_VALUE;

  final SqlCommand command = new SqlCommand();
  final SqlQuery query = new SqlQuery();
  final BoundSqlQuery executableQuery = new BoundSqlQuery();
  final SqlBoundProjectionPrograms projectionPrograms =
      new SqlBoundProjectionPrograms();
  final SqlBoundAggregateSet aggregates = new SqlBoundAggregateSet();
  final SqlBoundBooleanPredicateProgram whereBoolean =
      new SqlBoundBooleanPredicateProgram();
  private SqlBoundJoinContext[] joinContexts;
  private final SqlBoundBooleanPredicateProgram[] nestedBooleans =
      new SqlBoundBooleanPredicateProgram[io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlBoundProjectionPrograms[] nestedProjections =
      new SqlBoundProjectionPrograms[io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS];
  final SqlBoundBooleanPredicateProgram havingBoolean =
      new SqlBoundBooleanPredicateProgram();
  private SqlBoundBlockPlans blockPlans;
  final TableDefinition table = new TableDefinition();
  final int[] insertSourceByColumn = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updatedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updateResultTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];

  int predicateCount;
  int updatedColumnCount;
  int projectedColumnCount;
  int groupColumn;
  int groupAggregateColumn;
  int distinctColumn;
  int orderColumn;
  int sortKeyProjection;

  void reset() {
    command.reset();
    query.reset();
    executableQuery.reset();
    projectionPrograms.reset();
    aggregates.reset();
    whereBoolean.reset();
    if (joinContexts != null) {
      for (SqlBoundJoinContext context : joinContexts) {
        if (context != null) context.reset();
      }
    }
    for (int depth = 0; depth < nestedBooleans.length; depth++) {
      if (nestedBooleans[depth] != null) nestedBooleans[depth].reset();
      if (nestedProjections[depth] != null) nestedProjections[depth].reset();
    }
    havingBoolean.reset();
    if (blockPlans != null) blockPlans.reset();
    table.reset();
    resetRootAccess();
    predicateCount = 0;
    updatedColumnCount = 0;
    projectedColumnCount = 0;
    groupColumn = -1;
    groupAggregateColumn = -1;
    distinctColumn = -1;
    orderColumn = -1;
    sortKeyProjection = -1;
  }

  SqlBoundBlockPlans blockPlans() {
    if (blockPlans == null) blockPlans = new SqlBoundBlockPlans();
    return blockPlans;
  }

  SqlBoundJoinContext joinContext(int block) {
    if (block < 0 || block >= SqlQuery.MAXIMUM_QUERY_BLOCKS) return null;
    if (joinContexts == null) {
      joinContexts = new SqlBoundJoinContext[SqlQuery.MAXIMUM_QUERY_BLOCKS];
    }
    if (joinContexts[block] == null) joinContexts[block] = new SqlBoundJoinContext();
    return joinContexts[block];
  }

  SqlBoundJoinContext existingJoinContext(int block) {
    return joinContexts == null || block < 0 || block >= joinContexts.length
        ? null : joinContexts[block];
  }

  boolean hasBlockPlans() {
    return blockPlans != null && blockPlans.count() > 0;
  }

  SqlBoundBooleanPredicateProgram nestedBoolean(int block) {
    if (block < 0 || block >= nestedBooleans.length) return null;
    if (nestedBooleans[block] == null) {
      nestedBooleans[block] = new SqlBoundBooleanPredicateProgram();
    }
    return nestedBooleans[block];
  }

  SqlBoundProjectionPrograms nestedProjection(int block) {
    if (block < 0 || block >= nestedProjections.length) return null;
    if (nestedProjections[block] == null) {
      nestedProjections[block] = new SqlBoundProjectionPrograms();
    }
    return nestedProjections[block];
  }
}
