package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;

/** Reusable catalog-resolved state borrowed by one statement execution. */
final class BoundSqlStatement {
  static final int NULL_PROJECTION = Integer.MIN_VALUE;

  final SqlCommand command = new SqlCommand();
  final SqlQuery query = new SqlQuery();
  final BoundSqlQuery executableQuery = new BoundSqlQuery();
  final SqlBoundProjectionPrograms projectionPrograms =
      new SqlBoundProjectionPrograms();
  final SqlBoundAggregateSet aggregates = new SqlBoundAggregateSet();
  final SqlBoundBooleanPredicateProgram whereBoolean =
      new SqlBoundBooleanPredicateProgram();
  private final SqlBoundBooleanPredicateProgram[] nestedBooleans =
      new SqlBoundBooleanPredicateProgram[io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlBoundProjectionPrograms[] nestedProjections =
      new SqlBoundProjectionPrograms[io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private SqlBoundBooleanPredicateProgram onBoolean;
  final SqlBoundBooleanPredicateProgram havingBoolean =
      new SqlBoundBooleanPredicateProgram();
  private SqlBoundBlockPlans blockPlans;
  final TableDefinition table = new TableDefinition();
  final TableDefinition joinTable = new TableDefinition();
  final int[] insertSourceByColumn = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updatedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updateResultTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];

  int predicateColumn;
  int predicateCount;
  int accessPredicate;
  int pointTextColumn;
  long accessValue;
  long accessLowerInclusive;
  long accessUpperExclusive;
  SqlComparison accessComparison;
  int updatedColumnCount;
  int projectedColumnCount;
  int groupColumn;
  int groupAggregateColumn;
  int distinctColumn;
  int joinOuterColumn;
  int joinInnerColumn;
  int orderColumn;
  int sortKeyProjection;

  void reset() {
    command.reset();
    query.reset();
    executableQuery.reset();
    projectionPrograms.reset();
    aggregates.reset();
    whereBoolean.reset();
    for (int depth = 0; depth < nestedBooleans.length; depth++) {
      if (nestedBooleans[depth] != null) nestedBooleans[depth].reset();
      if (nestedProjections[depth] != null) nestedProjections[depth].reset();
    }
    if (onBoolean != null) onBoolean.reset();
    havingBoolean.reset();
    if (blockPlans != null) blockPlans.reset();
    table.reset();
    joinTable.reset();
    predicateColumn = -1;
    predicateCount = 0;
    accessPredicate = -1;
    pointTextColumn = -1;
    accessValue = 0;
    accessLowerInclusive = 0;
    accessUpperExclusive = 0;
    accessComparison = null;
    updatedColumnCount = 0;
    projectedColumnCount = 0;
    groupColumn = -1;
    groupAggregateColumn = -1;
    distinctColumn = -1;
    joinOuterColumn = -1;
    joinInnerColumn = -1;
    orderColumn = -1;
    sortKeyProjection = -1;
  }

  SqlBoundBlockPlans blockPlans() {
    if (blockPlans == null) blockPlans = new SqlBoundBlockPlans();
    return blockPlans;
  }

  SqlBoundBooleanPredicateProgram onBoolean() {
    if (onBoolean == null) onBoolean = new SqlBoundBooleanPredicateProgram();
    return onBoolean;
  }

  boolean hasOnBoolean() { return onBoolean != null && onBoolean.available(); }

  void resetOnBoolean() {
    if (onBoolean != null) onBoolean.reset();
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
