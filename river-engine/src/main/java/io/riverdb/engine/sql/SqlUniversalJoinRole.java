package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;

/** Small descriptor-or-legacy role facade for the universal join source. */
final class SqlUniversalJoinRole {
  private final SqlUniversalDescriptorJoinRole descriptor;
  private final SqlUniversalLegacyJoinRole legacy;
  private TableDefinition table;
  private boolean usesDescriptor;

  SqlUniversalJoinRole(RelationalSession session) {
    descriptor = new SqlUniversalDescriptorJoinRole(session);
    legacy = new SqlUniversalLegacyJoinRole(session);
  }

  StatusCode resolve(CharSequence name, TableDefinition table, StatusDetail detail) {
    this.table = table;
    StatusCode status = descriptor.resolve(name, table, detail);
    if (status == StatusCode.CONFLICT) status = legacy.resolve(name, table);
    usesDescriptor = status.isOk() && descriptor.resolved();
    if (!status.isOk()) this.table = null;
    return status;
  }

  void configure(
      io.riverdb.sql.SqlCommand command, int role, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where) {
    if (usesDescriptor) descriptor.configure(command, role, context, where);
    if (usesDescriptor && role == 0 && context.strategy(0) == SqlJoinStrategy.MERGE) {
      descriptor.configureMergeRoot(context.strategyOuterColumn(0));
    }
  }

  StatusCode open(SqlUniversalJoinRows rows) {
    return usesDescriptor ? descriptor.open(rows) : legacy.open();
  }
  StatusCode open(
      SqlUniversalJoinRows rows, SqlNestedRowProvider ancestors) {
    return usesDescriptor ? descriptor.open(rows, ancestors) : legacy.open();
  }
  StatusCode openFullScan() {
    return usesDescriptor ? descriptor.openFullScan() : legacy.open();
  }
  StatusCode next() { return usesDescriptor ? descriptor.next() : legacy.next(); }
  StatusCode closeScan() {
    return usesDescriptor ? descriptor.closeScan() : legacy.closeScan();
  }

  StatusCode reset() {
    StatusCode status = descriptor.reset();
    if (status.isOk()) status = legacy.reset();
    if (status.isOk()) usesDescriptor = false;
    if (status.isOk()) table = null;
    return status;
  }

  boolean descriptor() { return usesDescriptor; }
  boolean indexed() { return usesDescriptor && descriptor.indexed(); }
  boolean exact() { return usesDescriptor && descriptor.exact(); }
  boolean unique() { return usesDescriptor && descriptor.unique(); }
  int exactUniqueOuterColumns(
      int sourceRole, TableDefinition source, int projectedInnerColumn, int[] target) {
    return usesDescriptor
        ? descriptor.exactUniqueOuterColumns(
            sourceRole, source, projectedInnerColumn, target) : -1;
  }
  int accessColumn() { return usesDescriptor ? descriptor.accessColumn() : -1; }
  boolean hasResources() {
    return descriptor.hasResources() || legacy.hasResources();
  }
  long key() { return usesDescriptor ? descriptor.key() : legacy.key(); }
  long publicKey() { return usesDescriptor ? descriptor.publicKey() : legacy.key(); }
  SqlBlockRow row() { return usesDescriptor ? descriptor.row() : legacy.row(); }
  TableDefinition table() { return table; }
}
