package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.btree.TupleKeyCodec;

/** Protects one prepared tuple plan in global key-id then user-key order. */
final class RelationalDescriptorTupleDeltaProtection {
  StatusCode protect(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan) {
    if (session == null || !plan.matches(table)) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int index = 0; index < plan.keyCount(); index++) {
      StatusCode status = protectKey(session, plan, index);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode protectKey(
      IndexedTransactionSession session,
      RelationalDescriptorTupleDeltaPlan plan,
      int index) {
    if (plan.kind() == RelationalDescriptorTupleDeltaPlan.INSERT) {
      return protect(session, plan, index, true);
    }
    if (plan.kind() == RelationalDescriptorTupleDeltaPlan.DELETE) {
      return protect(session, plan, index, false);
    }
    if (!plan.changedAt(index)) return StatusCode.OK;
    int compared = TupleKeyCodec.compareUserTuple(
        plan.bytes(), plan.beforeOffsetAt(index), plan.beforeLengthAt(index),
        plan.bytes(), plan.afterOffsetAt(index), plan.afterLengthAt(index));
    boolean firstAfter = compared > 0;
    StatusCode status = protect(session, plan, index, firstAfter);
    return status.isOk() ? protect(session, plan, index, !firstAfter) : status;
  }

  private static StatusCode protect(
      IndexedTransactionSession session,
      RelationalDescriptorTupleDeltaPlan plan,
      int index,
      boolean after) {
    int offset = after ? plan.afterOffsetAt(index) : plan.beforeOffsetAt(index);
    int length = after ? plan.afterLengthAt(index) : plan.beforeLengthAt(index);
    StatusCode status = session.tupleWriteProtectionStatus(
        plan.keyAt(index).keyId(), plan.bytes(), offset, length);
    return status.isOk() ? status : status == StatusCode.NOT_OWNER
        ? session.protectTupleKeyForWrite(
            plan.keyAt(index).keyId(), plan.bytes(), offset, length) : status;
  }
}
