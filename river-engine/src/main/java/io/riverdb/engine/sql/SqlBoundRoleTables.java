package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.relational.TableDefinition;

/** Actual-count table handles for roles bound in one query block. */
final class SqlBoundRoleTables {
  private static final long CHARGED_BYTES_PER_SLOT = 32;
  private final SqlSessionShapeBudget budget;
  private TableDefinition[] tables = new TableDefinition[0];
  private TableDefinition[] owned = new TableDefinition[0];
  private boolean[] owns = new boolean[0];
  private boolean[] descriptors = new boolean[0];
  private int used;

  SqlBoundRoleTables(SqlSessionShapeBudget shapeBudget) { budget = shapeBudget; }

  StatusCode reserve(int required) {
    if (required < 1 || required > SqlShapeLimits.MAX_JOIN_ROLES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (required <= tables.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        tables.length, required, SqlShapeLimits.MAX_JOIN_ROLES, 1);
    long charged = (capacity - tables.length) * CHARGED_BYTES_PER_SLOT;
    StatusCode status = budget.reserve(charged);
    if (!status.isOk()) return status;
    try {
      TableDefinition[] nextTables = new TableDefinition[capacity];
      TableDefinition[] nextOwned = new TableDefinition[capacity];
      boolean[] nextOwns = new boolean[capacity];
      boolean[] nextDescriptors = new boolean[capacity];
      System.arraycopy(tables, 0, nextTables, 0, tables.length);
      System.arraycopy(owned, 0, nextOwned, 0, owned.length);
      System.arraycopy(owns, 0, nextOwns, 0, owns.length);
      System.arraycopy(descriptors, 0, nextDescriptors, 0, descriptors.length);
      tables = nextTables;
      owned = nextOwned;
      owns = nextOwns;
      descriptors = nextDescriptors;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void reset() {
    for (int role = 0; role < used; role++) clear(role);
    used = 0;
  }

  TableDefinition writable(int role) {
    if (!valid(role)) return null;
    if (owned[role] == null) {
      try {
        owned[role] = new TableDefinition();
      } catch (OutOfMemoryError error) {
        return null;
      }
    }
    clear(role);
    owned[role].reset();
    tables[role] = owned[role];
    owns[role] = true;
    used = Math.max(used, role + 1);
    return tables[role];
  }

  void bind(int role, TableDefinition definition) {
    if (!valid(role)) return;
    clear(role);
    tables[role] = definition;
    used = Math.max(used, role + 1);
  }

  void markDescriptor(int role) {
    if (valid(role) && role < used) descriptors[role] = true;
  }

  boolean descriptor(int role, int roleCount) {
    return role >= 0 && role < roleCount && role < used && descriptors[role];
  }

  TableDefinition get(int role, int roleCount) {
    return role >= 0 && role < roleCount && role < used ? tables[role] : null;
  }

  private boolean valid(int role) { return role >= 0 && role < tables.length; }

  private void clear(int role) {
    if (owns[role] && tables[role] != null) tables[role].reset();
    tables[role] = null;
    owns[role] = false;
    descriptors[role] = false;
  }
}
