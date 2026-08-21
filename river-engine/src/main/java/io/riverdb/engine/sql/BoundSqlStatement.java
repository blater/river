package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlJoinChain;
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
  private SqlBoundBooleanPredicateProgram[] onBooleans;
  final SqlBoundBooleanPredicateProgram havingBoolean =
      new SqlBoundBooleanPredicateProgram();
  private SqlBoundBlockPlans blockPlans;
  final TableDefinition table = new TableDefinition();
  private TableDefinition[] joinTables;
  private int joinRoleCount;
  final int[] insertSourceByColumn = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updatedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updateResultTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  private final byte[] joinAccessOuterRoles =
      new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] joinAccessOuterColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] joinAccessInnerColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] joinStrategies =
      new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] joinStrategyOuterRoles =
      new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] joinStrategyOuterColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] joinStrategyInnerColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];

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
  int orderColumn;
  int sortKeyProjection;

  void reset() {
    command.reset();
    query.reset();
    executableQuery.reset();
    projectionPrograms.reset();
    aggregates.reset();
    whereBoolean.reset();
    resetOnBoolean();
    havingBoolean.reset();
    if (blockPlans != null) blockPlans.reset();
    table.reset();
    resetJoinRoles();
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
    resetJoinAccess();
    resetJoinStrategies();
    orderColumn = -1;
    sortKeyProjection = -1;
  }

  SqlBoundBlockPlans blockPlans() {
    if (blockPlans == null) blockPlans = new SqlBoundBlockPlans();
    return blockPlans;
  }

  SqlBoundBooleanPredicateProgram onBoolean(int stage) {
    if (stage < 0 || stage >= SqlJoinChain.MAXIMUM_JOIN_STAGES) return null;
    if (onBooleans == null) {
      onBooleans = new SqlBoundBooleanPredicateProgram[
          SqlJoinChain.MAXIMUM_JOIN_STAGES];
    }
    if (onBooleans[stage] == null) {
      onBooleans[stage] = new SqlBoundBooleanPredicateProgram();
    }
    return onBooleans[stage];
  }

  boolean hasOnBoolean(int stage) {
    return onBooleans != null && stage >= 0 && stage < onBooleans.length
        && onBooleans[stage] != null && onBooleans[stage].available();
  }

  void resetOnBoolean() {
    if (onBooleans == null) return;
    for (SqlBoundBooleanPredicateProgram program : onBooleans) {
      if (program != null) program.reset();
    }
  }

  void beginJoinRoles(int roles) {
    joinRoleCount = roles;
    if (roles <= 1) return;
    if (joinTables == null) {
      joinTables = new TableDefinition[SqlJoinChain.MAXIMUM_JOIN_ROLES - 1];
    }
    for (int role = 1; role < roles; role++) {
      if (joinTables[role - 1] == null) {
        joinTables[role - 1] = new TableDefinition();
      }
    }
  }

  TableDefinition joinRole(int role) {
    if (role < 0 || role >= joinRoleCount) return null;
    if (role == 0) return table;
    return joinTables == null ? null : joinTables[role - 1];
  }

  private void resetJoinRoles() {
    if (joinTables != null) {
      for (TableDefinition definition : joinTables) {
        if (definition != null) definition.reset();
      }
    }
    joinRoleCount = 0;
  }

  void resetJoinAccess() {
    for (int stage = 0; stage < joinAccessOuterRoles.length; stage++) {
      joinAccessOuterRoles[stage] = -1;
      joinAccessOuterColumns[stage] = -1;
      joinAccessInnerColumns[stage] = -1;
    }
  }

  void setJoinAccess(int stage, int outerRole, int outerColumn, int innerColumn) {
    joinAccessOuterRoles[stage] = (byte) outerRole;
    joinAccessOuterColumns[stage] = outerColumn;
    joinAccessInnerColumns[stage] = innerColumn;
  }

  int joinAccessOuterRole(int stage) { return joinAccessOuterRoles[stage]; }
  int joinAccessOuterColumn(int stage) { return joinAccessOuterColumns[stage]; }
  int joinAccessInnerColumn(int stage) { return joinAccessInnerColumns[stage]; }

  void resetJoinStrategies() {
    for (int stage = 0; stage < joinStrategies.length; stage++) {
      joinStrategies[stage] = SqlJoinStrategy.NESTED_LOOP;
      joinStrategyOuterRoles[stage] = -1;
      joinStrategyOuterColumns[stage] = -1;
      joinStrategyInnerColumns[stage] = -1;
    }
  }

  void setJoinStrategy(
      int stage,
      int strategy,
      int outerRole,
      int outerColumn,
      int innerColumn) {
    joinStrategies[stage] = (byte) strategy;
    joinStrategyOuterRoles[stage] = (byte) outerRole;
    joinStrategyOuterColumns[stage] = outerColumn;
    joinStrategyInnerColumns[stage] = innerColumn;
  }

  int joinStrategy(int stage) { return Byte.toUnsignedInt(joinStrategies[stage]); }
  int joinStrategyOuterRole(int stage) { return joinStrategyOuterRoles[stage]; }
  int joinStrategyOuterColumn(int stage) { return joinStrategyOuterColumns[stage]; }
  int joinStrategyInnerColumn(int stage) { return joinStrategyInnerColumns[stage]; }

  boolean hasPhysicalJoinStrategy() {
    return physicalJoinStrategyStage() >= 0;
  }

  int physicalJoinStrategyStage() {
    for (int stage = 0; stage < joinStrategies.length; stage++) {
      if (Byte.toUnsignedInt(joinStrategies[stage]) != SqlJoinStrategy.NESTED_LOOP) {
        return stage;
      }
    }
    return -1;
  }

  boolean hasBlockPlans() {
    return blockPlans != null && blockPlans.count() > 0;
  }
}
