package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Uniqueness consumers of the canonical user views derived from physical delta slices. */
final class RelationalDescriptorTupleDeltaValidation {
  StatusCode validate(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan, long logicalRowId) {
    if (session == null || logicalRowId <= 0 || !plan.matches(table)
        || plan.kind() == RelationalDescriptorTupleDeltaPlan.DELETE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < plan.keyCount(); index++) {
      if (plan.kind() == RelationalDescriptorTupleDeltaPlan.UPDATE
          && !plan.changedAt(index)) continue;
      KeyDescriptor key = plan.keyAt(index);
      if (!key.isUnique() || nullableUnique(plan, index, key)) continue;
      ByteBuffer user = plan.userKey(index, true);
      StatusCode status = session.validateTupleUniquePrefix(
          table.tableId(), key.keyId(), key.keyId(), key.shape(),
          user, 0, plan.userLength(index, true), logicalRowId);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode admit(
      TableDescriptor table, RelationalDescriptorTupleDeltaPlan plan,
      RelationalDescriptorInsertBatch batch) {
    if (batch == null || !plan.matches(table)
        || plan.kind() != RelationalDescriptorTupleDeltaPlan.INSERT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < plan.keyCount(); index++) {
      KeyDescriptor key = plan.keyAt(index);
      if (!key.isUnique() || nullableUnique(plan, index, key)) continue;
      ByteBuffer user = plan.userKey(index, true);
      StatusCode status = batch.admitUnique(
          key.keyId(), user, plan.userLength(index, true));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static boolean nullableUnique(
      RelationalDescriptorTupleDeltaPlan plan, int index, KeyDescriptor key) {
    return key.kind() != KeyDescriptor.KIND_PRIMARY
        && TupleKeyCodec.containsNull(
            plan.bytes(), plan.afterOffsetAt(index), plan.afterLengthAt(index));
  }
}
