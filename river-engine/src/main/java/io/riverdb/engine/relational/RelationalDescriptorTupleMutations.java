package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Owns one reusable tuple delta plan from admission through physical staging. */
final class RelationalDescriptorTupleMutations {
  private final RelationalDescriptorTupleDeltaPlan plan;
  private final RelationalDescriptorTupleDeltaProtection protection =
      new RelationalDescriptorTupleDeltaProtection();
  private final RelationalDescriptorTupleDeltaValidation unique =
      new RelationalDescriptorTupleDeltaValidation();
  private final RelationalDescriptorTupleDeltaStaging staging =
      new RelationalDescriptorTupleDeltaStaging();
  private final RelationalDescriptorTupleDeltaAdmission admission =
      new RelationalDescriptorTupleDeltaAdmission();
  private final RelationalDescriptorForeignValidation foreignValidation =
      new RelationalDescriptorForeignValidation();
  private final int[] singleRowLengths = new int[1];

  RelationalDescriptorTupleMutations() {
    plan = new RelationalDescriptorTupleDeltaPlan();
  }

  StatusCode planInsert(TableDescriptor table, SqlValueBuffer values, long logicalRowId) {
    return plan.insert(table, values, logicalRowId);
  }

  StatusCode planUpdate(
      TableDescriptor table, SqlValueBuffer before,
      SqlValueBuffer after, long logicalRowId) {
    return plan.update(table, before, after, logicalRowId);
  }

  StatusCode planDelete(TableDescriptor table, SqlValueBuffer values, long logicalRowId) {
    return plan.delete(table, values, logicalRowId);
  }

  StatusCode bindLogicalRowId(long logicalRowId) { return plan.bindLogicalRowId(logicalRowId); }

  StatusCode validateInsert(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer values, long logicalRowId) {
    return validateInsert(session, table, values, logicalRowId, true);
  }

  StatusCode validateInsert(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer values, long logicalRowId, boolean foreignKeys) {
    StatusCode status = protection.protect(session, table, plan);
    if (status.isOk()) status = unique.validate(session, table, plan, logicalRowId);
    return status.isOk() && foreignKeys
        ? foreignValidation.validate(session, table, values) : status;
  }

  StatusCode validateUpdate(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer before, SqlValueBuffer after, long logicalRowId) {
    StatusCode status = unique.validate(session, table, plan, logicalRowId);
    return status.isOk()
        ? foreignValidation.validateUpdate(session, table, before, after) : status;
  }

  StatusCode validateUniqueAdmission(
      TableDescriptor table, RelationalDescriptorInsertBatch batch) {
    return unique.admit(table, plan, batch);
  }

  StatusCode validateForeign(
      IndexedTransactionSession session, TableDescriptor table, SqlValueBuffer values) {
    return foreignValidation.validate(session, table, values);
  }

  StatusCode protect(IndexedTransactionSession session, TableDescriptor table) {
    return protection.protect(session, table, plan);
  }

  StatusCode stage(
      IndexedTransactionSession session, TableDescriptor table, long logicalRowId) {
    return staging.stage(session, table, plan, logicalRowId);
  }

  StatusCode prepareDelete(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer values, long logicalRowId,
      RelationalDescriptorForeignKeyChecks foreignKeys) {
    StatusCode status = planDelete(table, values, logicalRowId);
    if (status.isOk()) status = preflightSingleRow(session, table, 1);
    if (status.isOk()) status = protect(session, table);
    return status.isOk() ? foreignKeys.checkDelete(table, values, logicalRowId) : status;
  }

  int mutationCount() { return plan.mutationCount(); }
  int payloadBytes() { return plan.payloadBytes(); }

  StatusCode preflightWithRows(
      IndexedTransactionSession session, TableDescriptor table,
      int[] rowLengths, int rowStart, int rowCount,
      int mutations, int bytes) {
    int descriptors = admission.additionalDescriptors(session, table, plan);
    return descriptors < 0 ? StatusCode.CORRUPTION
        : session.preflightRelationalMutations(
            rowLengths, rowStart, rowCount, mutations, descriptors, bytes);
  }

  StatusCode preflightSingleRow(
      IndexedTransactionSession session, TableDescriptor table, int rowBytes) {
    singleRowLengths[0] = rowBytes;
    return preflightWithRows(
        session, table, singleRowLengths, 0, 1,
        mutationCount(), payloadBytes());
  }
}
