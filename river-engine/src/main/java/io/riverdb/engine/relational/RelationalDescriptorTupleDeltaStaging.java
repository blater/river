package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedRelationalMutation;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Appends the exact durable mutations represented by one prepared tuple plan. */
final class RelationalDescriptorTupleDeltaStaging {
  StatusCode stage(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan, long logicalRowId) {
    if (session == null || logicalRowId <= 0 || !plan.matches(table)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < plan.keyCount(); index++) {
      StatusCode status = stageKey(session, table, plan, index, logicalRowId);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode stageKey(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan, int index, long logicalRowId) {
    if (plan.kind() == RelationalDescriptorTupleDeltaPlan.INSERT) {
      return append(session, table, plan, index, true, logicalRowId);
    }
    StatusCode status = plan.kind() == RelationalDescriptorTupleDeltaPlan.DELETE
        || plan.changedAt(index)
            ? append(session, table, plan, index, false, logicalRowId) : StatusCode.OK;
    return status.isOk() && plan.kind() == RelationalDescriptorTupleDeltaPlan.UPDATE
        && plan.changedAt(index)
            ? append(session, table, plan, index, true, logicalRowId) : status;
  }

  private static StatusCode append(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan, int index,
      boolean insert, long logicalRowId) {
    KeyDescriptor key = plan.keyAt(index);
    return session.appendTupleMutation(
        insert ? IndexedRelationalMutation.TUPLE_INSERT
            : IndexedRelationalMutation.TUPLE_DELETE,
        table.tableId(), key.keyId(), key.keyId(), key.shape(), logicalRowId,
        plan.bytes(), insert ? plan.afterOffsetAt(index) : plan.beforeOffsetAt(index),
        insert ? plan.afterLengthAt(index) : plan.beforeLengthAt(index));
  }
}
