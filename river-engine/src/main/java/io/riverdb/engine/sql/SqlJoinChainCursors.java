package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns one physical cursor and borrowed result carrier for every JOIN role. */
final class SqlJoinChainCursors {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final RelationalScanCursor[] cursors =
      new RelationalScanCursor[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final RelationalScanResult[] scans =
      new RelationalScanResult[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final ValueIndexLookupResult[] indexed =
      new ValueIndexLookupResult[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final HeapRowResult[] fetched =
      new HeapRowResult[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final long[] keys = new long[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final HeapRowResult[] rows =
      new HeapRowResult[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private boolean rootValueIndex;
  private int activeRoleCount;
  private final boolean[] rightIndexed =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] rightUnique =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];

  SqlJoinChainCursors(RelationalSession relationalSession, BoundSqlStatement statement) {
    session = relationalSession;
    bound = statement;
    for (int role = 0; role < cursors.length; role++) {
      cursors[role] = new RelationalScanCursor();
      scans[role] = new RelationalScanResult();
      indexed[role] = new ValueIndexLookupResult();
      fetched[role] = new HeapRowResult();
    }
  }

  StatusCode beginRoot() {
    configureRights();
    activeRoleCount = bound.command.joinChain().roleCount();
    if (bound.joinStrategy(0) == SqlJoinStrategy.MERGE) {
      int column = bound.joinStrategyOuterColumn(0);
      rootValueIndex = column > 0;
      return rootValueIndex
          ? session.beginValueScan(bound.table, column, cursors[0])
          : session.beginScan(bound.table, cursors[0]);
    }
    boolean predicate = bound.accessPredicate >= 0;
    boolean equality = predicate && bound.accessComparison == SqlComparison.EQUAL;
    rootValueIndex = predicate && bound.predicateColumn > 0
        && bound.table.hasIndexOn(bound.predicateColumn);
    boolean primary = predicate && bound.predicateColumn == 0;
    long lower = !predicate ? 0
        : equality ? bound.accessValue : bound.accessLowerInclusive;
    long upper = !predicate || equality ? 0 : bound.accessUpperExclusive;
    if (rootValueIndex) {
      return equality
          ? session.beginExactValueScan(
              bound.table, bound.predicateColumn, lower, cursors[0])
          : session.beginValueScan(
              bound.table, bound.predicateColumn, lower, upper, cursors[0]);
    }
    return primary
        ? equality
            ? session.beginExactScan(bound.table, lower, cursors[0])
            : session.beginScan(bound.table, lower, upper, cursors[0])
        : session.beginScan(bound.table, cursors[0]);
  }

  StatusCode nextRoot() {
    StatusCode status = rootValueIndex
        ? session.nextValueScan(bound.table, cursors[0], scans[0], indexed[0])
        : session.nextScan(cursors[0], scans[0]);
    if (!status.isOk()) return status;
    keys[0] = rootValueIndex ? indexed[0].key() : scans[0].key();
    rows[0] = rootValueIndex ? indexed[0].row() : scans[0].row();
    return validate(0);
  }

  StatusCode beginRole(int role, long value) {
    TableDefinition table = bound.joinRole(role);
    int stage = role - 1;
    return rightIndexed[stage]
        ? session.beginNonUniqueValueLookup(
            table, bound.joinAccessInnerColumn(stage), value, cursors[role])
        : session.beginScan(table, cursors[role]);
  }

  StatusCode nextRole(int role) {
    TableDefinition table = bound.joinRole(role);
    int stage = role - 1;
    StatusCode status = rightIndexed[stage]
        ? session.nextNonUniqueValueLookup(table, cursors[role], indexed[role])
        : session.nextScan(cursors[role], scans[role]);
    if (!status.isOk()) return status;
    keys[role] = rightIndexed[stage]
        ? indexed[role].key() : scans[role].key();
    rows[role] = rightIndexed[stage]
        ? indexed[role].row() : scans[role].row();
    return validate(role);
  }

  StatusCode fetchRole(int role, long value) {
    int stage = role - 1;
    int innerColumn = bound.joinAccessInnerColumn(stage);
    TableDefinition table = bound.joinRole(role);
    StatusCode status = innerColumn == 0
        ? session.fetch(table, value, fetched[role])
        : session.fetchByUniqueValue(
            table, innerColumn, value, indexed[role]);
    if (!status.isOk()) return status;
    keys[role] = innerColumn == 0 ? value : indexed[role].key();
    rows[role] = innerColumn == 0 ? fetched[role] : indexed[role].row();
    return validate(role);
  }

  StatusCode closeRole(int role) {
    StatusCode status = session.closeScan(cursors[role]);
    if (status.isOk()) status = cursors[role].reset();
    if (status.isOk()) clear(role);
    return status;
  }

  StatusCode closeAll() {
    for (int role = activeRoleCount - 1; role >= 0; role--) {
      if (!cursors[role].isActive()) continue;
      StatusCode status = closeRole(role);
      if (!status.isOk()) return status;
    }
    for (int role = 0; role < activeRoleCount; role++) {
      StatusCode status = cursors[role].reset();
      if (!status.isOk()) return status;
      clear(role);
    }
    activeRoleCount = 0;
    return StatusCode.OK;
  }

  private void configureRights() {
    for (int stage = 0; stage < bound.command.joinChain().stageCount(); stage++) {
      int right = bound.joinAccessInnerColumn(stage);
      boolean access = bound.joinAccessOuterColumn(stage) >= 0 && right >= 0;
      TableDefinition table = bound.joinRole(stage + 1);
      rightIndexed[stage] = access && (right == 0 || table.hasIndexOn(right));
      rightUnique[stage] = access && (right == 0 || table.hasUniqueIndexOn(right));
    }
  }

  boolean hasResources() {
    for (RelationalScanCursor cursor : cursors) {
      if (cursor.isActive()) return true;
    }
    return false;
  }

  boolean rootValueIndex() { return rootValueIndex; }
  boolean rightIndexed(int stage) { return rightIndexed[stage]; }
  boolean rightUnique(int stage) { return rightUnique[stage]; }
  boolean roleActive(int role) { return cursors[role].isActive(); }
  long key(int role) { return keys[role]; }
  HeapRowResult row(int role) { return rows[role]; }

  private StatusCode validate(int role) {
    TableDefinition table = bound.joinRole(role);
    HeapRowResult row = rows[role];
    return row.length() >= table.fixedRowBytes()
        && row.length() <= table.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private void clear(int role) {
    scans[role].reset();
    indexed[role].reset();
    fetched[role].reset();
    keys[role] = 0;
    rows[role] = null;
  }
}
