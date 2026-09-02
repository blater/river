package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.btree.TupleBTreeScanBounds;

/** Reusable typed bounds for one descriptor role's selected tuple index. */
final class SqlUniversalDescriptorIndexAccess {
  private final SqlUniversalDescriptorBounds preparedBounds =
      new SqlUniversalDescriptorBounds();
  private final SqlUniversalDescriptorIndexChoice choice =
      new SqlUniversalDescriptorIndexChoice();
  private TableDescriptor table;
  private SqlCommand command;
  private long tableId;
  private long rowLayoutId;
  private long catalogGeneration;
  private int direction = TupleBTreeScanBounds.FORWARD;
  private boolean empty;

  void prepare(
      SqlCommand source, TableDescriptor descriptor, int role,
      SqlBoundJoinContext context, SqlBoundBooleanPredicateProgram where) {
    prepare(source, descriptor, role, context, where, null);
  }

  void prepare(
      SqlCommand source, TableDescriptor descriptor, int role,
      SqlBoundJoinContext context, SqlBoundBooleanPredicateProgram where,
      SqlBlockColumnLineage lineage) {
    reset();
    table = descriptor;
    command = source;
    if (descriptor != null) {
      tableId = descriptor.tableId();
      rowLayoutId = descriptor.rowLayoutId();
      catalogGeneration = descriptor.catalogGeneration();
    }
    SqlUniversalDescriptorIndexSelector.select(
        table, role, context, where, lineage, choice);
  }

  StatusCode bind(SqlUniversalJoinRows rows) {
    if (!active()) return StatusCode.CONFLICT;
    return preparedBounds.bind(table, command, choice, rows, direction);
  }

  boolean active() { return choice.key != null; }
  boolean empty() { return empty; }
  void markEmpty() { empty = true; }
  boolean matches(TableDescriptor candidate) {
    return candidate != null
        && candidate.tableId() == tableId
        && candidate.rowLayoutId() == rowLayoutId
        && candidate.catalogGeneration() == catalogGeneration;
  }
  boolean exact() { return choice.exact(); }
  boolean unique() { return choice.unique(); }
  int accessColumn() {
    if (!active()) return -1;
    if (choice.key.kind() == KeyDescriptor.KIND_PRIMARY) return 0;
    for (int part = 0; part < choice.key.partCount(); part++) {
      if (choice.key.columnOrdinalAt(part) > 0) return choice.key.columnOrdinalAt(part);
    }
    return 1;
  }
  io.riverdb.engine.relational.RelationalDescriptorIndexBounds bounds() {
    return preparedBounds.bounds();
  }

  void reset() {
    preparedBounds.reset();
    choice.reset();
    table = null;
    command = null;
    tableId = 0;
    rowLayoutId = 0;
    catalogGeneration = 0;
    direction = TupleBTreeScanBounds.FORWARD;
    empty = false;
  }
}
