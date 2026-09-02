package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableStatistics;
import io.riverdb.sql.SqlJoinChain;

/** Transactionally grows every peer array and retained object of a bound JOIN context. */
final class SqlBoundJoinContextAdmission {
  private SqlBoundJoinContextAdmission() { }

  static StatusCode prepare(SqlBoundJoinContext target, int roles) {
    int capacity = BoundedArrayGrowth.capacity(
        target.tables.length, roles, SqlJoinChain.MAXIMUM_JOIN_ROLES, 2);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == target.tables.length) return StatusCode.OK;
    try {
      return grow(target, capacity);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  static StatusCode begin(
      SqlBoundJoinContext target, int roles, TableDefinition borrowedRoot) {
    StatusCode status = prepare(target, roles);
    if (!status.isOk()) return status;
    target.queryBlock = -1;
    target.roleCount = roles;
    for (int role = 0; role < roles; role++) activate(target, role, borrowedRoot);
    return StatusCode.OK;
  }

  static StatusCode borrow(
      SqlBoundJoinContext target, int block, BoundSqlQuery.Block schemas) {
    target.reset();
    int roles = schemas == null ? 0 : schemas.roleCount();
    StatusCode status = prepare(target, roles);
    if (!status.isOk()) return status;
    target.queryBlock = block;
    target.roleCount = roles;
    for (int role = 0; role < roles; role++) {
      target.tables[role] = schemas.table(role);
      target.ownedTables[role] = false;
    }
    return StatusCode.OK;
  }

  private static void activate(
      SqlBoundJoinContext target, int role, TableDefinition borrowedRoot) {
    if (role == 0 && borrowedRoot != null) {
      if (target.ownedTables[role] && target.tables[role] != null) {
        target.tables[role].reset();
      }
      target.tables[role] = borrowedRoot;
      target.ownedTables[role] = false;
      return;
    }
    if (!target.ownedTables[role]) target.ownedRoleTables[role].reset();
    target.tables[role] = target.ownedRoleTables[role];
    target.ownedTables[role] = true;
  }

  private static StatusCode grow(SqlBoundJoinContext target, int capacity) {
    SqlJoinContextAllocator allocator = target.allocator;
    TableDefinition[] tables = allocator.tables(capacity);
    TableDefinition[] owned = allocator.tables(capacity);
    boolean[] flags = allocator.booleans(capacity);
    TableStatistics[] statistics = allocator.statistics(capacity);
    int stages = capacity - 1;
    SqlBoundBooleanPredicateProgram[] on = allocator.predicates(stages);
    byte[] accessRoles = allocator.bytes(stages);
    int[] accessOuter = allocator.integers(stages);
    int[] accessInner = allocator.integers(stages);
    byte[] strategies = allocator.bytes(stages);
    byte[] strategyRoles = allocator.bytes(stages);
    int[] strategyOuter = allocator.integers(stages);
    int[] strategyInner = allocator.integers(stages);
    long[] estimated = allocator.longs(stages);
    copy(target, tables, owned, flags, statistics, on, accessRoles,
        accessOuter, accessInner, strategies, strategyRoles,
        strategyOuter, strategyInner, estimated);
    for (int role = target.tables.length; role < capacity; role++) {
      owned[role] = allocator.table();
      statistics[role] = allocator.statistic();
    }
    for (int stage = target.onBooleans.length; stage < stages; stage++) {
      on[stage] = allocator.predicate();
    }
    publish(target, tables, owned, flags, statistics, on, accessRoles,
        accessOuter, accessInner, strategies, strategyRoles,
        strategyOuter, strategyInner, estimated);
    return StatusCode.OK;
  }

  private static void copy(
      SqlBoundJoinContext source, TableDefinition[] tables,
      TableDefinition[] owned, boolean[] flags, TableStatistics[] statistics,
      SqlBoundBooleanPredicateProgram[] on, byte[] accessRoles,
      int[] accessOuter, int[] accessInner, byte[] strategies,
      byte[] strategyRoles, int[] strategyOuter, int[] strategyInner,
      long[] estimated) {
    int roles = source.tables.length;
    int stages = source.onBooleans.length;
    System.arraycopy(source.tables, 0, tables, 0, roles);
    System.arraycopy(source.ownedRoleTables, 0, owned, 0, roles);
    System.arraycopy(source.ownedTables, 0, flags, 0, roles);
    System.arraycopy(source.statistics, 0, statistics, 0, roles);
    System.arraycopy(source.onBooleans, 0, on, 0, stages);
    System.arraycopy(source.accessOuterRoles, 0, accessRoles, 0, stages);
    System.arraycopy(source.accessOuterColumns, 0, accessOuter, 0, stages);
    System.arraycopy(source.accessInnerColumns, 0, accessInner, 0, stages);
    System.arraycopy(source.strategies, 0, strategies, 0, stages);
    System.arraycopy(source.strategyOuterRoles, 0, strategyRoles, 0, stages);
    System.arraycopy(source.strategyOuterColumns, 0, strategyOuter, 0, stages);
    System.arraycopy(source.strategyInnerColumns, 0, strategyInner, 0, stages);
    System.arraycopy(source.estimatedRows, 0, estimated, 0, stages);
  }

  private static void publish(
      SqlBoundJoinContext target, TableDefinition[] tables,
      TableDefinition[] owned, boolean[] flags, TableStatistics[] statistics,
      SqlBoundBooleanPredicateProgram[] on, byte[] accessRoles,
      int[] accessOuter, int[] accessInner, byte[] strategies,
      byte[] strategyRoles, int[] strategyOuter, int[] strategyInner,
      long[] estimated) {
    target.tables = tables;
    target.ownedRoleTables = owned;
    target.ownedTables = flags;
    target.statistics = statistics;
    target.onBooleans = on;
    target.accessOuterRoles = accessRoles;
    target.accessOuterColumns = accessOuter;
    target.accessInnerColumns = accessInner;
    target.strategies = strategies;
    target.strategyOuterRoles = strategyRoles;
    target.strategyOuterColumns = strategyOuter;
    target.strategyInnerColumns = strategyInner;
    target.estimatedRows = estimated;
    target.resetJoinAccess();
    target.resetStrategies();
  }
}
