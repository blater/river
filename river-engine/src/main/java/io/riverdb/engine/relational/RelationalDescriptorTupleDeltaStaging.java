package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedRelationalMutation;
import io.riverdb.engine.table.IndexedTransactionSession;
import java.nio.ByteBuffer;

/** Appends the exact key and value mutations represented by one prepared tuple plan. */
final class RelationalDescriptorTupleDeltaStaging {
  StatusCode stage(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan, long logicalRowId,
      ByteBuffer row, int rowLength) {
    if (session == null || logicalRowId <= 0 || !plan.matches(table)
        || plan.kind() != RelationalDescriptorTupleDeltaPlan.DELETE
            && (row == null || rowLength <= 0 || rowLength > row.limit())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < plan.keyCount(); index++) {
      StatusCode status = stageKey(
          session, table, plan, index, logicalRowId, row, rowLength);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode stageKey(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan, int index, long logicalRowId,
      ByteBuffer row, int rowLength) {
    int kind = plan.kind();
    if (kind == RelationalDescriptorTupleDeltaPlan.INSERT) {
      return append(session, table, plan, index,
          IndexedRelationalMutation.TUPLE_INSERT, logicalRowId, row, rowLength);
    }
    if (kind == RelationalDescriptorTupleDeltaPlan.DELETE) {
      return append(session, table, plan, index,
          IndexedRelationalMutation.TUPLE_DELETE, logicalRowId, null, 0);
    }
    if (plan.changedAt(index)) {
      StatusCode status = append(session, table, plan, index,
          IndexedRelationalMutation.TUPLE_DELETE, logicalRowId, null, 0);
      return status.isOk() ? append(session, table, plan, index,
          IndexedRelationalMutation.TUPLE_INSERT, logicalRowId, row, rowLength) : status;
    }
    return index == plan.primaryIndex() || plan.primaryChanged()
        ? append(session, table, plan, index,
            IndexedRelationalMutation.TUPLE_REPLACE, logicalRowId, row, rowLength)
        : StatusCode.OK;
  }

  private static StatusCode append(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan, int index, int operation,
      long logicalRowId, ByteBuffer row, int rowLength) {
    KeyDescriptor key = plan.keyAt(index);
    boolean after = operation != IndexedRelationalMutation.TUPLE_DELETE;
    ByteBuffer value = null;
    int valueOffset = 0;
    int valueLength = 0;
    if (after && index == plan.primaryIndex()) {
      value = row;
      valueLength = rowLength;
    } else if (after && plan.primaryIndex() >= 0) {
      value = plan.bytes();
      valueOffset = plan.afterOffsetAt(plan.primaryIndex());
      valueLength = plan.afterLengthAt(plan.primaryIndex());
    }
    return session.appendTupleMutation(
        operation,
        table.tableId(), key.keyId(), key.keyId(), key.shape(), logicalRowId,
        plan.bytes(), after ? plan.afterOffsetAt(index) : plan.beforeOffsetAt(index),
        after ? plan.afterLengthAt(index) : plan.beforeLengthAt(index),
        value, valueOffset, valueLength);
  }
}
