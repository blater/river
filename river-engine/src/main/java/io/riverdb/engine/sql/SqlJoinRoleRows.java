package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable role-indexed JOIN tuple with owned rows required by nested cursors. */
class SqlJoinRoleRows {
  private final SqlJoinResourceAllocator allocator;
  private SqlBoundJoinContext context;
  private long[] keys = new long[0];
  private HeapRowResult[] borrowed = new HeapRowResult[0];
  private SqlJoinOuterRow[] owned = new SqlJoinOuterRow[0];
  private boolean[] owns = new boolean[0];
  private boolean[] nulls = new boolean[0];
  private int roleCount;

  SqlJoinRoleRows() { this(SqlJoinResourceAllocator.STANDARD); }

  SqlJoinRoleRows(SqlJoinResourceAllocator resourceAllocator) {
    allocator = resourceAllocator;
  }

  StatusCode configure(SqlBoundJoinContext joinContext, int roles) {
    StatusCode status = prepare(roles);
    if (status.isOk()) {
      context = joinContext;
      roleCount = roles;
    }
    return status;
  }

  StatusCode prepare(int roles) {
    int capacity = BoundedArrayGrowth.capacity(
        keys.length, roles, SqlJoinChain.MAXIMUM_JOIN_ROLES, 2);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == keys.length) return prepareRows(roles);
    try {
      long[] nextKeys = allocator.longs(capacity);
      HeapRowResult[] nextBorrowed = allocator.heapRows(capacity);
      SqlJoinOuterRow[] nextOwned = allocator.outerRows(capacity);
      boolean[] nextOwns = allocator.booleans(capacity);
      boolean[] nextNulls = allocator.booleans(capacity);
      System.arraycopy(keys, 0, nextKeys, 0, roleCount);
      System.arraycopy(borrowed, 0, nextBorrowed, 0, roleCount);
      System.arraycopy(owned, 0, nextOwned, 0, roleCount);
      System.arraycopy(owns, 0, nextOwns, 0, roleCount);
      System.arraycopy(nulls, 0, nextNulls, 0, roleCount);
      for (int role = roleCount; role < capacity; role++) {
        nextOwned[role] = allocator.outerRow();
      }
      StatusCode status = prepareRows(nextOwned, roles);
      if (!status.isOk()) return status;
      keys = nextKeys;
      borrowed = nextBorrowed;
      owned = nextOwned;
      owns = nextOwns;
      nulls = nextNulls;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void borrow(int role, long key, HeapRowResult row) {
    clear(role);
    keys[role] = key;
    borrowed[role] = row;
  }

  void setNull(int role) {
    clear(role);
    nulls[role] = true;
  }

  StatusCode own(int role) {
    if (nulls[role] || owns[role]) return StatusCode.OK;
    StatusCode status = owned[role].capture(borrowed[role]);
    if (status.isOk()) {
      owns[role] = true;
      borrowed[role] = null;
    }
    return status;
  }

  StatusCode ownThrough(int lastRole) {
    for (int role = 0; role <= lastRole; role++) {
      StatusCode status = own(role);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  long key(int role) { return keys[role]; }
  HeapRowResult row(int role) {
    return nulls[role] ? null : owns[role] ? owned[role].row() : borrowed[role];
  }
  TableDefinition table(int role) { return context.table(role); }
  boolean nullRole(int role) { return nulls[role]; }

  void clearFrom(int firstRole) {
    for (int role = firstRole; role < roleCount; role++) clear(role);
  }

  void reset() { clearFrom(0); }

  private void clear(int role) {
    owned[role].reset();
    keys[role] = 0;
    borrowed[role] = null;
    owns[role] = false;
    nulls[role] = false;
  }

  private StatusCode prepareRows(int roles) { return prepareRows(owned, roles); }

  private static StatusCode prepareRows(SqlJoinOuterRow[] rows, int roles) {
    for (int role = 0; role < roles; role++) {
      StatusCode status = rows[role].prepare();
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }
}
