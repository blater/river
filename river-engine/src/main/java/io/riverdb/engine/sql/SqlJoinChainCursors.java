package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns one physical cursor and borrowed result carrier for every JOIN role. */
final class SqlJoinChainCursors {
  private final RelationalSession session;
  private final SqlJoinResourceAllocator allocator;
  private SqlBoundJoinContext context;
  private SqlCommand command;
  private RelationalScanCursor[] cursors = new RelationalScanCursor[0];
  private RelationalScanResult[] scans = new RelationalScanResult[0];
  private ValueIndexLookupResult[] indexed = new ValueIndexLookupResult[0];
  private HeapRowResult[] fetched = new HeapRowResult[0];
  private long[] keys = new long[0];
  private HeapRowResult[] rows = new HeapRowResult[0];
  private boolean rootValueIndex;
  private int activeRoleCount;
  private boolean[] rightIndexed = new boolean[0];
  private boolean[] rightUnique = new boolean[0];

  SqlJoinChainCursors(RelationalSession relationalSession) {
    this(relationalSession, SqlJoinResourceAllocator.STANDARD);
  }

  SqlJoinChainCursors(
      RelationalSession relationalSession,
      SqlJoinResourceAllocator resourceAllocator) {
    session = relationalSession;
    allocator = resourceAllocator;
  }

  StatusCode configure(SqlBoundJoinContext joinContext, SqlCommand canonicalCommand) {
    int roles = canonicalCommand.joinChain().roleCount();
    StatusCode status = prepare(roles);
    if (!status.isOk()) return status;
    context = joinContext;
    command = canonicalCommand;
    return StatusCode.OK;
  }

  StatusCode prepare(int roles) {
    int capacity = BoundedArrayGrowth.capacity(
        cursors.length, roles, SqlJoinChain.MAXIMUM_JOIN_ROLES, 2);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == cursors.length) return StatusCode.OK;
    try {
      RelationalScanCursor[] nextCursors = allocator.cursors(capacity);
      RelationalScanResult[] nextScans = allocator.scans(capacity);
      ValueIndexLookupResult[] nextIndexed = allocator.lookups(capacity);
      HeapRowResult[] nextFetched = allocator.heapRows(capacity);
      long[] nextKeys = allocator.longs(capacity);
      HeapRowResult[] nextRows = allocator.heapRows(capacity);
      boolean[] nextRightIndexed = allocator.booleans(capacity - 1);
      boolean[] nextRightUnique = allocator.booleans(capacity - 1);
      for (int role = 0; role < capacity; role++) {
        nextCursors[role] = role < cursors.length ? cursors[role] : allocator.cursor();
        nextScans[role] = role < scans.length ? scans[role] : allocator.scan();
        nextIndexed[role] = role < indexed.length ? indexed[role] : allocator.lookup();
        nextFetched[role] = role < fetched.length ? fetched[role] : allocator.heapRow();
      }
      cursors = nextCursors;
      scans = nextScans;
      indexed = nextIndexed;
      fetched = nextFetched;
      keys = nextKeys;
      rows = nextRows;
      rightIndexed = nextRightIndexed;
      rightUnique = nextRightUnique;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode beginRoot() {
    configureRights();
    activeRoleCount = command.joinChain().roleCount();
    TableDefinition root = context.table(0);
    if (context.strategy(0) == SqlJoinStrategy.MERGE) {
      int column = context.strategyOuterColumn(0);
      rootValueIndex = column > 0;
      return rootValueIndex
          ? session.beginValueScan(root, column, cursors[0])
          : session.beginScan(root, cursors[0]);
    }
    boolean predicate = context.accessPredicate >= 0;
    boolean equality = predicate && context.accessComparison == SqlComparison.EQUAL;
    rootValueIndex = predicate && context.predicateColumn > 0
        && root.hasIndexOn(context.predicateColumn);
    boolean primary = predicate && context.predicateColumn == 0;
    long lower = !predicate ? 0
        : equality ? context.accessValue : context.accessLowerInclusive;
    long upper = !predicate || equality ? 0 : context.accessUpperExclusive;
    if (rootValueIndex) {
      return equality
          ? session.beginExactValueScan(
              root, context.predicateColumn, lower, cursors[0])
          : session.beginValueScan(
              root, context.predicateColumn, lower, upper, cursors[0]);
    }
    return primary
        ? equality
            ? session.beginExactScan(root, lower, cursors[0])
            : session.beginScan(root, lower, upper, cursors[0])
        : session.beginScan(root, cursors[0]);
  }

  StatusCode nextRoot() {
    StatusCode status = rootValueIndex
        ? session.nextValueScan(context.table(0), cursors[0], scans[0], indexed[0])
        : session.nextScan(cursors[0], scans[0]);
    if (!status.isOk()) return status;
    keys[0] = rootValueIndex ? indexed[0].key() : scans[0].key();
    rows[0] = rootValueIndex ? indexed[0].row() : scans[0].row();
    return validate(0);
  }

  StatusCode beginRole(int role, long value) {
    TableDefinition table = context.table(role);
    int stage = role - 1;
    return rightIndexed[stage]
        ? session.beginNonUniqueValueLookup(
            table, context.accessInnerColumn(stage), value, cursors[role])
        : session.beginScan(table, cursors[role]);
  }

  StatusCode nextRole(int role) {
    TableDefinition table = context.table(role);
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
    int innerColumn = context.accessInnerColumn(stage);
    TableDefinition table = context.table(role);
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
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      int right = context.accessInnerColumn(stage);
      boolean access = context.accessOuterColumn(stage) >= 0 && right >= 0;
      TableDefinition table = context.table(stage + 1);
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
    TableDefinition table = context.table(role);
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
