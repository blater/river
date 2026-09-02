package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.TransactionState;

/** Executes an admitted successor build after proposal planning has completed. */
final class CatalogSuccessorBuildFlow {
  private final SchemaCache cache;
  private final CatalogTransactions transactions;
  private final CatalogBuildCleaner cleaner;
  private final CatalogPrivateTableBuild privateBuild;

  CatalogSuccessorBuildFlow(
      SchemaCache schemaCache,
      CatalogTransactions flow,
      CatalogPrivateTableBuild tableBuild,
      CatalogBuildCleaner buildCleaner) {
    cache = schemaCache;
    transactions = flow;
    privateBuild = tableBuild;
    cleaner = buildCleaner;
  }

  StatusCode prepare(
      TableDescriptor current,
      TableDescriptor proposed,
      IndexedTransactionSession publicationSession,
      IndexedTransactionSession buildSession,
      CatalogTablePayloadPlan plan,
      CatalogBuildAdmission admission,
      CatalogReservation reservation,
      TableDescriptor.Result descriptor,
      CatalogPreparedTable prepared,
      boolean stagePublication,
      StatusDetail detail) {
    StatusCode status = privateBuild.reserveSuccessorIds(
        buildSession, current, proposed, plan, reservation);
    if (status.isOk()) status = CatalogSuccessorKeyBinding.bind(
        current, proposed, reservation, descriptor, detail);
    if (status.isOk()) reservation.setPhysicalIndexCount(
        CatalogTableKeys.reservedPhysicalIndexCount(descriptor.value(), reservation));
    if (status.isOk()) status = cache.reserveSuccessor(
        descriptor.value(), current.catalogGeneration(), prepared.admission());
    if (!status.isOk()) return fail(detail, status);
    prepared.activate(
        descriptor.value(), reservation, plan.totalPayloadBytes(), admission.catalogBytes());
    status = privateBuild.createIntent(buildSession, reservation, plan, admission);
    TransactionState intentState = transactions.lastState();
    if (intentState == TransactionState.COMMITTED) prepared.markDurableBuildKnown();
    if (intentState == TransactionState.INDETERMINATE) prepared.forgetIntentCommitOutcome();
    if (status.isOk()) status = privateBuild.writeDefinition(
        buildSession, descriptor.value(), reservation, plan, admission);
    if (status.isOk()) status = privateBuild.validateDefinition(
        buildSession, reservation, descriptor);
    if (status.isOk()) status = privateBuild.buildIndexes(
        buildSession, descriptor.value(), reservation, plan, admission);
    if (status.isOk() && stagePublication) {
      status = privateBuild.stageReady(
          publicationSession, descriptor.value(), reservation);
      if (status.isOk()) prepared.markPublicationStaged();
    }
    return status.isOk()
        ? succeed(detail) : cancel(prepared, reservation, status, intentState, detail);
  }

  private StatusCode cancel(
      CatalogPreparedTable prepared,
      CatalogReservation reservation,
      StatusCode status,
      TransactionState intentState,
      StatusDetail detail) {
    if (intentState == TransactionState.COMMITTED
        || intentState == TransactionState.INDETERMINATE) return fail(detail, status);
    if (prepared.admission().isActive()) prepared.admission().cancel();
    StatusCode cleanup = cleaner.cleanup(reservation.objectId());
    prepared.clear();
    return fail(detail, status.isOk() ? cleanup : status);
  }

  private static StatusCode succeed(StatusDetail detail) {
    if (detail != null) detail.set(StatusCode.OK);
    return StatusCode.OK;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status) {
    if (detail != null && detail.code() == StatusCode.OK) detail.set(status);
    return status;
  }
}
