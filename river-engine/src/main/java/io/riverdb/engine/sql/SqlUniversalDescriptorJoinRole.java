package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalDescriptorJoinTableView;
import io.riverdb.engine.relational.RelationalDescriptorIndexBounds;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Reopenable streaming cursor for one descriptor-backed universal-join role. */
final class SqlUniversalDescriptorJoinRole {
  private final RelationalSession session;
  private final SchemaPin bindingPin = new SchemaPin();
  private final SqlUniversalDescriptorRoleScan scan;
  private final RelationalDescriptorJoinTableView bindingView =
      new RelationalDescriptorJoinTableView();
  private final SqlUniversalDescriptorJoinRow current =
      new SqlUniversalDescriptorJoinRow();
  private final SqlUniversalDescriptorName name = new SqlUniversalDescriptorName();
  private final SqlUniversalDescriptorIndexAccess access =
      new SqlUniversalDescriptorIndexAccess();
  private final RelationalDescriptorIndexBounds mergeBounds =
      new RelationalDescriptorIndexBounds();
  private SqlUniversalDescriptorIndexAccess fixedAccess;
  private TableDescriptor descriptor;
  private int mergeColumn = -1;

  SqlUniversalDescriptorJoinRole(RelationalSession relationalSession) {
    session = relationalSession;
    scan = new SqlUniversalDescriptorRoleScan(relationalSession);
  }

  StatusCode resolve(CharSequence source, TableDefinition binding, StatusDetail detail) {
    StatusCode status = name.set(source);
    if (status.isOk()) status = session.resolveDescriptor(name, bindingPin, detail);
    if (!status.isOk()) return status;
    descriptor = bindingPin.descriptor();
    status = bindingView.prepare(descriptor, binding);
    if (status.isOk()) status = current.prepare(descriptor);
    return status;
  }

  boolean resolved() { return descriptor != null; }

  void configure(
      io.riverdb.sql.SqlCommand command, int role, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where) {
    access.prepare(command, descriptor, role, context, where);
  }

  void configureMergeRoot(int column) {
    mergeColumn = -1;
    KeyDescriptor key = SqlUniversalDescriptorOrderedKey.find(descriptor, column);
    if (key == null) return;
    StatusCode status = mergeBounds.set(
        key, null, 0, true, null, 0, true,
        io.riverdb.storage.btree.TupleBTreeScanBounds.FORWARD);
    if (status.isOk()) mergeColumn = column;
  }

  void configureRoot(
      io.riverdb.sql.SqlCommand command, SqlBoundBooleanPredicateProgram where) {
    configureRoot(command, where, -1);
  }

  void configureRoot(
      io.riverdb.sql.SqlCommand command, SqlBoundBooleanPredicateProgram where,
      int queryBlock) {
    fixedAccess = null;
    access.prepare(command, descriptor, 0, null, where, null, queryBlock);
  }

  void configureRoot(SqlUniversalDescriptorIndexAccess prepared) {
    fixedAccess = prepared != null && prepared.active() ? prepared : null;
  }

  StatusCode open(SqlUniversalJoinRows rows) {
    return open(rows, null, false);
  }

  StatusCode open(
      SqlUniversalJoinRows rows, SqlNestedRowProvider ancestors) {
    return open(rows, ancestors, false);
  }

  StatusCode open(SqlNestedRowProvider ancestors) {
    return open(null, ancestors, false);
  }

  StatusCode openFullScan() {
    return open(null, null, true);
  }

  private StatusCode open(
      SqlUniversalJoinRows rows, SqlNestedRowProvider ancestors,
      boolean fullScan) {
    if (descriptor == null) return StatusCode.CONFLICT;
    return scan.open(
        name, descriptor, access, fixedAccess, rows, ancestors,
        fullScan, mergeColumn, mergeBounds);
  }

  StatusCode open() { return open((SqlUniversalJoinRows) null); }

  StatusCode next() {
    return scan.next(current, descriptor);
  }

  StatusCode closeScan() {
    return scan.close();
  }

  StatusCode reset() {
    StatusCode status = closeScan();
    if (status.isOk() && bindingPin.isActive()) status = bindingPin.release();
    if (status.isOk()) {
      descriptor = null;
      access.reset();
      fixedAccess = null;
      mergeColumn = -1;
      current.reset();
      name.reset();
    }
    return status;
  }

  long key() { return current.key(); }
  long publicKey() { return current.publicKey(); }
  SqlBlockRow row() { return current.row(); }
  boolean indexed() {
    return mergeColumn >= 0
        || (fixedAccess == null ? access.active() : fixedAccess.active());
  }
  boolean exact() { return fixedAccess == null ? access.exact() : fixedAccess.exact(); }
  boolean unique() { return fixedAccess == null ? access.unique() : fixedAccess.unique(); }
  int exactUniqueOuterColumns(
      int sourceRole, TableDefinition source, int projectedInnerColumn, int[] target) {
    SqlUniversalDescriptorIndexAccess selected = fixedAccess == null ? access : fixedAccess;
    return selected.exactUniqueOuterColumns(
        sourceRole, source, projectedInnerColumn, target);
  }
  int accessColumn() {
    return mergeColumn >= 0 ? mergeColumn
        : fixedAccess == null ? access.accessColumn() : fixedAccess.accessColumn();
  }
  boolean hasResources() { return scan.hasResources() || bindingPin.isActive(); }
}
