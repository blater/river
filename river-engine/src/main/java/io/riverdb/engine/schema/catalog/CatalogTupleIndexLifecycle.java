package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.IsolationLevel;

/** Builds private tuple roots and atomically stages their catalog publication. */
final class CatalogTupleIndexLifecycle {
  private static final int BUILD_BATCH_INDEXES = 32;
  private final CatalogTransactions transactions;
  private final CatalogIntentStore intents;

  CatalogTupleIndexLifecycle(CatalogTransactions flow, CatalogIntentStore intentStore) {
    transactions = flow;
    intents = intentStore;
  }

  StatusCode build(
      IndexedTransactionSession session, TableDescriptor table,
      CatalogReservation reservation, int payloadBytes, long catalogBytes) {
    int first = reservation.nextPhysicalIndex();
    while (first < reservation.physicalIndexCount()) {
      int count = Math.min(BUILD_BATCH_INDEXES, reservation.physicalIndexCount() - first);
      StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
      if (status.isOk()) status = session.preflightTupleIndexLifecycles(count);
      for (int index = 0; status.isOk() && index < count; index++) {
        status = stageBuilding(session, table, reservation, first + index);
      }
      if (status.isOk()) status = intents.updateIndexProgress(
          session, reservation, first + count, payloadBytes, catalogBytes);
      status = transactions.finish(session, status, true);
      if (!status.isOk()) return status;
      first += count;
      reservation.setNextPhysicalIndex(first);
    }
    return StatusCode.OK;
  }

  StatusCode stageReady(
      IndexedTransactionSession session, TableDescriptor table,
      CatalogReservation reservation) {
    int count = reservation.physicalIndexCount();
    StatusCode status = count == 0
        ? StatusCode.OK : session.preflightTupleIndexLifecycles(count);
    for (int index = 0; status.isOk() && index < count; index++) {
      KeyDescriptor key = CatalogTableKeys.reservedPhysicalIndexAt(table, reservation, index);
      status = key == null ? StatusCode.CORRUPTION : session.stageTupleIndexReady(
          table.tableId(), key.keyId(), key.keyId(),
          reservation.schemaId(), key.shape());
    }
    return status;
  }

  private static StatusCode stageBuilding(
      IndexedTransactionSession session, TableDescriptor table,
      CatalogReservation reservation, int ordinal) {
    KeyDescriptor key = CatalogTableKeys.reservedPhysicalIndexAt(table, reservation, ordinal);
    return key == null ? StatusCode.CORRUPTION : session.stageTupleIndexBuilding(
        table.tableId(), key.keyId(), key.keyId(),
        reservation.schemaId(), key.shape());
  }
}
