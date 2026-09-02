package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Reusable catalog-resolved state borrowed by one statement execution. */
final class BoundSqlStatement extends SqlBoundAccess {
  static final int NULL_PROJECTION = Integer.MIN_VALUE;

  final SqlCommand command = new SqlCommand();
  final SqlQuery query = new SqlQuery();
  final BoundSqlQuery executableQuery;
  final SqlBoundProjectionPrograms projectionPrograms;
  final SqlBoundAggregateSet aggregates;
  final SqlBoundAggregateSet joinedAggregates;
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
  int[] insertSourceByColumn;
  int[] updatedColumns;
  int[] updateResultTypeDescriptors;
  int[] projectedColumns;
  int[] projectedTypeDescriptors;

  int predicateCount;
  int updatedColumnCount;
  int projectedColumnCount;
  int joinProjectedColumnCount;
  int groupColumn;
  int groupAggregateColumn;
  int distinctColumn;
  int orderColumn;
  int sortKeyProjection;
  boolean expandedView;
  private final SqlSessionShapeBudget budget;
  private final SqlBoundStatementColumns columns;

  BoundSqlStatement() {
    this(new SqlSessionShapeBudget(null));
  }

  BoundSqlStatement(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
    columns = new SqlBoundStatementColumns(budget);
    aggregates = new SqlBoundAggregateSet(budget);
    joinedAggregates = new SqlBoundAggregateSet(budget);
    updateColumnArrays();
    executableQuery = new BoundSqlQuery(budget);
    projectionPrograms = new SqlBoundProjectionPrograms(budget);
  }

  StatusCode reserveInsertColumns(int columns) {
    StatusCode status = this.columns.reserveInsert(columns);
    updateColumnArrays();
    return status;
  }

  StatusCode reserveMutationColumns(int columns) {
    StatusCode status = projectionPrograms.reserveMutations(columns);
    if (status.isOk()) status = this.columns.reserveMutation(columns);
    updateColumnArrays();
    return status;
  }

  StatusCode reserveProjectionColumns(int columns) {
    StatusCode status = this.columns.reserveProjection(columns);
    if (status.isOk()) status = projectionPrograms.reserve(columns);
    updateColumnArrays();
    return status;
  }

  private void updateColumnArrays() {
    insertSourceByColumn = columns.insert();
    updatedColumns = columns.mutationColumns();
    updateResultTypeDescriptors = columns.mutationTypes();
    projectedColumns = columns.projectionColumns();
    projectedTypeDescriptors = columns.projectionTypes();
  }

  void reset() {
    command.reset();
    query.reset();
    executableQuery.reset();
    projectionPrograms.reset();
    aggregates.reset();
    joinedAggregates.reset();
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
    joinProjectedColumnCount = 0;
    groupColumn = -1;
    groupAggregateColumn = -1;
    distinctColumn = -1;
    orderColumn = -1;
    sortKeyProjection = -1;
    expandedView = false;
  }

  SqlBoundBlockPlans blockPlans() {
    if (blockPlans == null) blockPlans = new SqlBoundBlockPlans(budget);
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
      nestedProjections[block] = new SqlBoundProjectionPrograms(budget);
    }
    return nestedProjections[block];
  }
}
