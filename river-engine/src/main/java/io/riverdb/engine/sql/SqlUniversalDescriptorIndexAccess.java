package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
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
    prepare(
        source, descriptor, role, context, where, lineage,
        context == null ? -1 : context.queryBlock);
  }

  void prepare(
      SqlCommand source, TableDescriptor descriptor, int role,
      SqlBoundJoinContext context, SqlBoundBooleanPredicateProgram where,
      SqlBlockColumnLineage lineage, int queryBlock) {
    reset();
    table = descriptor;
    command = source;
    if (descriptor != null) {
      tableId = descriptor.tableId();
      rowLayoutId = descriptor.rowLayoutId();
      catalogGeneration = descriptor.catalogGeneration();
    }
    SqlUniversalDescriptorIndexSelector.select(
        table, role, context, where, lineage, queryBlock, choice);
  }

  StatusCode bind(SqlUniversalJoinRows rows) {
    return bind(rows, null);
  }

  StatusCode bind(
      SqlUniversalJoinRows rows, SqlNestedRowProvider ancestors) {
    if (!active()) return StatusCode.CONFLICT;
    return preparedBounds.bind(
        table, command, choice, rows, ancestors, direction);
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

  int exactUniqueOuterColumns(
      int sourceRole, io.riverdb.engine.relational.TableDefinition source,
      int projectedInnerColumn, int[] target) {
    if (!exact() || !unique() || choice.key.kind() != KeyDescriptor.KIND_PRIMARY
        || source == null || target == null
        || target.length < choice.equalParts) {
      return -1;
    }
    int count = 0;
    boolean projectedKeyPart = false;
    for (int part = 0; part < choice.equalParts; part++) {
      if (choice.key.columnOrdinalAt(part) == projectedInnerColumn) {
        projectedKeyPart = true;
      }
      SqlUniversalDescriptorIndexBinding binding = choice.equal[part];
      if (binding.literal()) continue;
      if (!binding.outerFrom(sourceRole)) return -1;
      int column = binding.outerColumn();
      int descriptor = choice.key.typeDescriptorAt(part);
      int type = SqlTypeDescriptor.typeId(descriptor);
      if (column < 0 || column >= source.columnCount()
          || source.typeDescriptor(column) != descriptor
          || type != SqlTypeDescriptor.TYPE_ID_SMALLINT
              && type != SqlTypeDescriptor.TYPE_ID_INTEGER
              && type != SqlTypeDescriptor.TYPE_ID_BIGINT) {
        return -1;
      }
      target[count++] = column;
    }
    return projectedKeyPart ? count : -1;
  }

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
