package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Counts descriptor growth directly from the ordered keys retained by a tuple plan. */
final class RelationalDescriptorTupleDeltaAdmission {
  int additionalDescriptors(
      IndexedTransactionSession session, TableDescriptor table,
      RelationalDescriptorTupleDeltaPlan plan) {
    if (session == null || !plan.matches(table)) return -1;
    int additional = 0;
    for (int index = 0; index < plan.keyCount(); index++) {
      KeyDescriptor key = plan.keyAt(index);
      StatusCode status = session.tupleDescriptorStatus(
          table.tableId(), key.keyId(), key.keyId(), key.shape());
      if (status == StatusCode.CONFLICT) additional++;
      else if (!status.isOk()) return -1;
    }
    return additional;
  }
}
