package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.IsolationLevel;
/** Writes and validates one admitted private table definition before publication. */
final class CatalogPrivateTableBuild {
  private static final int CHILD_BATCH_RECORDS = 32;
  private final CatalogTransactions transactions;
  private final CatalogIdAllocator allocator;
  private final CatalogDefinitionStore definitions;
  private final CatalogDefinitionWriter writer;
  private final CatalogObjectHeadStore heads;
  private final CatalogIntentStore intents;
  private final CatalogTupleIndexLifecycle indexes;

  CatalogPrivateTableBuild(
      CatalogTransactions flow,
      CatalogIdAllocator idAllocator,
      CatalogDefinitionStore definitionStore,
      CatalogDefinitionWriter definitionWriter,
      CatalogObjectHeadStore headStore,
      CatalogIntentStore intentStore) {
    transactions = flow;
    allocator = idAllocator;
    definitions = definitionStore;
    writer = definitionWriter;
    heads = headStore;
    intents = intentStore;
    indexes = new CatalogTupleIndexLifecycle(flow, intentStore);
  }
  StatusCode reserveIds(
      IndexedTransactionSession session,
      TableDescriptor table,
      CatalogTablePayloadPlan plan,
      CatalogReservation reservation) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) status = allocator.reserve(
        session, plan.chunkCount(), CatalogTableKeys.count(table), reservation);
    return transactions.finish(session, status, true);
  }

  StatusCode reserveSuccessorIds(
      IndexedTransactionSession session,
      TableDescriptor current,
      TableDescriptor proposed,
      CatalogTablePayloadPlan plan,
      CatalogReservation reservation) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) status = definitions.readAnyHead(session, current.tableId());
    if (status.isOk()
        && (definitions.headState()
            != io.riverdb.format.catalog.CatalogObjectHeadCodec.STATE_READY
            || definitions.headGeneration() != current.catalogGeneration())) {
      status = StatusCode.CONFLICT;
    }
    if (status.isOk()) status = definitions.readCurrentManifest(session, current.tableId());
    if (status.isOk() && definitions.currentRowLayoutId() != current.rowLayoutId()) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) status = allocator.reserveSuccessor(
        session, current, plan.chunkCount(), CatalogTableKeys.unboundCount(proposed),
        definitions.headSchemaId(), definitions.headManifestRecordId(), reservation);
    return transactions.finish(session, status, true);
  }

  StatusCode createIntent(
      IndexedTransactionSession session,
      CatalogReservation reservation,
      CatalogTablePayloadPlan plan,
      CatalogBuildAdmission admission) {
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) status = intents.insert(
        session, reservation, plan.totalPayloadBytes(), admission.catalogBytes());
    return transactions.finish(session, status, true);
  }

  StatusCode writeDefinition(
      IndexedTransactionSession session,
      TableDescriptor descriptor,
      CatalogReservation reservation,
      CatalogTablePayloadPlan plan,
      CatalogBuildAdmission admission) {
    int first = 0;
    while (first < plan.chunkCount()) {
      int count = Math.min(CHILD_BATCH_RECORDS, plan.chunkCount() - first);
      StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
      if (status.isOk()) status = writer.writeChildren(
          session, descriptor, reservation, plan, first, count);
      if (status.isOk()) status = intents.updateProgress(
          session, reservation, first + count,
          plan.totalPayloadBytes(), admission.catalogBytes());
      status = transactions.finish(session, status, true);
      if (!status.isOk()) return status;
      first += count;
    }
    StatusCode status = session.begin(IsolationLevel.SERIALIZABLE);
    if (status.isOk()) status = writer.writeManifest(
        session, descriptor, reservation, plan);
    return transactions.finish(session, status, true);
  }

  StatusCode validateDefinition(
      IndexedTransactionSession session,
      CatalogReservation reservation,
      TableDescriptor.Result descriptor) {
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) status = definitions.validatePrivate(
        session, reservation, descriptor, null);
    return transactions.finish(session, status, false);
  }

  StatusCode buildIndexes(
      IndexedTransactionSession session, TableDescriptor descriptor,
      CatalogReservation reservation, CatalogTablePayloadPlan plan,
      CatalogBuildAdmission admission) {
    return indexes.build(session, descriptor, reservation,
        plan.totalPayloadBytes(), admission.catalogBytes());
  }

  StatusCode stageReady(
      IndexedTransactionSession session, TableDescriptor descriptor,
      CatalogReservation reservation) {
    StatusCode status = indexes.stageReady(session, descriptor, reservation);
    if (!status.isOk()) return status;
    if (reservation.kind()
        != io.riverdb.format.catalog.CatalogBuildIntentCodec.KIND_SUCCESSOR) {
      return heads.insertReady(session, reservation);
    }
    status = definitions.validatePredecessor(session, reservation);
    return status.isOk() ? heads.updateReady(session, reservation) : status;
  }
}
