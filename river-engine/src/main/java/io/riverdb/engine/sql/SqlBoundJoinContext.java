package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableStatistics;
import io.riverdb.sql.SqlJoinChain;

/** Reusable catalog, predicate, access, and strategy state for one JOIN block. */
final class SqlBoundJoinContext extends SqlBoundAccess {
  final SqlJoinContextAllocator allocator;
  TableDefinition[] tables = new TableDefinition[0];
  TableDefinition[] ownedRoleTables = new TableDefinition[0];
  boolean[] ownedTables = new boolean[0];
  TableStatistics[] statistics = new TableStatistics[0];
  SqlBoundBooleanPredicateProgram[] onBooleans =
      new SqlBoundBooleanPredicateProgram[0];
  byte[] accessOuterRoles = new byte[0];
  int[] accessOuterColumns = new int[0];
  int[] accessInnerColumns = new int[0];
  byte[] strategies = new byte[0];
  byte[] strategyOuterRoles = new byte[0];
  int[] strategyOuterColumns = new int[0];
  int[] strategyInnerColumns = new int[0];
  long[] estimatedRows = new long[0];
  int roleCount;
  int queryBlock = -1;
  private boolean estimatesAvailable;

  SqlBoundJoinContext() { this(SqlJoinContextAllocator.STANDARD); }

  SqlBoundJoinContext(SqlJoinContextAllocator contextAllocator) {
    allocator = contextAllocator;
  }

  StatusCode beginRoles(int roles, TableDefinition borrowedRoot) {
    return SqlBoundJoinContextAdmission.begin(this, roles, borrowedRoot);
  }

  StatusCode borrowRoles(int block, BoundSqlQuery.Block schemas) {
    return SqlBoundJoinContextAdmission.borrow(this, block, schemas);
  }

  void usePackedScopes(int block) { queryBlock = block; }

  int localRole(int scope) {
    if (queryBlock < 0) return scope;
    return SqlNestedRowProvider.block(scope) == queryBlock
        ? SqlNestedRowProvider.role(scope) : -1;
  }

  TableDefinition table(int role) {
    return role < 0 || role >= roleCount ? null : tables[role];
  }

  TableStatistics statistics(int role) {
    return role < 0 || role >= roleCount ? null : statistics[role];
  }

  SqlBoundBooleanPredicateProgram onBoolean(int stage) {
    return stage < 0 || stage >= roleCount - 1 ? null : onBooleans[stage];
  }

  boolean hasOnBoolean(int stage) {
    return stage >= 0 && stage < onBooleans.length
        && onBooleans[stage] != null && onBooleans[stage].available();
  }

  void reset() {
    for (int role = 0; role < roleCount; role++) {
      if (ownedTables[role] && tables[role] != null) tables[role].reset();
      if (statistics[role] != null) statistics[role].reset();
      tables[role] = null;
      ownedTables[role] = false;
    }
    roleCount = 0;
    queryBlock = -1;
    resetOnBooleans();
    resetJoinAccess();
    resetStrategies();
    resetEstimates();
  }

  void resetOnBooleans() {
    for (SqlBoundBooleanPredicateProgram program : onBooleans) {
      if (program != null) program.reset();
    }
  }

  void resetJoinAccess() {
    resetRootAccess();
    for (int stage = 0; stage < accessOuterRoles.length; stage++) {
      accessOuterRoles[stage] = -1;
      accessOuterColumns[stage] = -1;
      accessInnerColumns[stage] = -1;
    }
  }

  void setAccess(int stage, int outerRole, int outerColumn, int innerColumn) {
    accessOuterRoles[stage] = (byte) outerRole;
    accessOuterColumns[stage] = outerColumn;
    accessInnerColumns[stage] = innerColumn;
  }

  int accessOuterRole(int stage) { return accessOuterRoles[stage]; }
  int accessOuterColumn(int stage) { return accessOuterColumns[stage]; }
  int accessInnerColumn(int stage) { return accessInnerColumns[stage]; }

  void resetStrategies() {
    for (int stage = 0; stage < strategies.length; stage++) clearStrategy(stage);
  }

  void clearStrategy(int stage) {
    strategies[stage] = SqlJoinStrategy.NESTED_LOOP;
    strategyOuterRoles[stage] = -1;
    strategyOuterColumns[stage] = -1;
    strategyInnerColumns[stage] = -1;
  }

  void setStrategy(
      int stage,
      int strategy,
      int outerRole,
      int outerColumn,
      int innerColumn) {
    strategies[stage] = (byte) strategy;
    strategyOuterRoles[stage] = (byte) outerRole;
    strategyOuterColumns[stage] = outerColumn;
    strategyInnerColumns[stage] = innerColumn;
  }

  int strategy(int stage) { return Byte.toUnsignedInt(strategies[stage]); }
  int strategyOuterRole(int stage) { return strategyOuterRoles[stage]; }
  int strategyOuterColumn(int stage) { return strategyOuterColumns[stage]; }
  int strategyInnerColumn(int stage) { return strategyInnerColumns[stage]; }

  void resetEstimates() {
    for (int stage = 0; stage < estimatedRows.length; stage++) {
      estimatedRows[stage] = 0;
    }
    estimatesAvailable = false;
  }

  void setEstimatedRows(int stage, long rows) {
    estimatedRows[stage] = rows;
    estimatesAvailable = true;
  }

  boolean estimatesAvailable() { return estimatesAvailable; }
  long estimatedRows(int stage) { return estimatedRows[stage]; }

  int physicalStrategyStage() {
    for (int stage = 0; stage < strategies.length; stage++) {
      if (Byte.toUnsignedInt(strategies[stage]) != SqlJoinStrategy.NESTED_LOOP) {
        return stage;
      }
    }
    return -1;
  }

  boolean hasPhysicalStrategy() { return physicalStrategyStage() >= 0; }

  StatusCode prepare(int roles) {
    return SqlBoundJoinContextAdmission.prepare(this, roles);
  }
}
