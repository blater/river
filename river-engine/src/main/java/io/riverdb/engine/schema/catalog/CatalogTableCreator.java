package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.TransactionState;

/** Bounded multi-transaction build whose READY head is the sole publication point. */
final class CatalogTableCreator {
  private final SchemaCache cache;
  private final CatalogTransactions transactions;
  private final CatalogDefinitionWriter writer;
  private final CatalogBuildCleaner cleaner;
  private final CatalogPrivateTableBuild privateBuild;
  private final CatalogPreparedTableCompletion completion;
  private final CatalogDirectTableCreation direct;
  private final CatalogTablePayloadPlan plan = new CatalogTablePayloadPlan();
  private final CatalogBuildAdmission buildAdmission = new CatalogBuildAdmission();
  private final CatalogReservation reservation = new CatalogReservation();
  private final TableDescriptor.Result descriptorResult = new TableDescriptor.Result();
  private final CatalogSessionResult opened = new CatalogSessionResult();
  private boolean intentDurable;

  CatalogTableCreator(
      SchemaCache schemaCache,
      CatalogTransactions transactionFlow,
      CatalogIdAllocator idAllocator,
      CatalogDefinitionStore definitionStore,
      CatalogDefinitionWriter definitionWriter,
      CatalogObjectHeadStore headStore,
      CatalogIntentStore intentStore,
      CatalogBuildCleaner buildCleaner) {
    cache = schemaCache;
    transactions = transactionFlow;
    writer = definitionWriter;
    cleaner = buildCleaner;
    privateBuild = new CatalogPrivateTableBuild(
        transactionFlow, idAllocator, definitionStore, definitionWriter,
        headStore, intentStore);
    completion = new CatalogPreparedTableCompletion(
        transactionFlow, intentStore, buildCleaner);
    direct = new CatalogDirectTableCreation(transactionFlow, completion);
  }

  StatusCode create(TableDescriptor provisional, SchemaPin pin, StatusDetail detail) {
    return direct.create(this, provisional, pin, detail);
  }

  StatusCode prepare(
      TableDescriptor provisional,
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    reset(detail);
    if (provisional == null || publicationSession == null || prepared == null
        || prepared.isActive()) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    StatusCode status = writer.plan(provisional, plan);
    if (status.isOk()) status = buildAdmission.admit(plan);
    if (!status.isOk()) return fail(detail, status);
    status = transactions.openBuild(opened);
    if (!status.isOk()) return fail(detail, status);
    IndexedTransactionSession buildSession = opened.session();
    status = privateBuild.reserveIds(buildSession, provisional, plan, reservation);
    if (status.isOk()) status = CatalogDescriptorIdentity.bind(
        provisional, reservation, descriptorResult, detail);
    TableDescriptor descriptor = status.isOk() ? descriptorResult.value() : null;
    if (status.isOk()) {
      reservation.setPhysicalIndexCount(
          CatalogTableKeys.reservedPhysicalIndexCount(descriptor, reservation));
      status = cache.reserveSuccessor(descriptor, 0, prepared.admission());
    }
    if (status.isOk()) {
      prepared.activate(
          descriptor, reservation, plan.totalPayloadBytes(), buildAdmission.catalogBytes());
      status = buildPrivate(
          buildSession, publicationSession, prepared, descriptor, detail);
    }
    StatusCode released = transactions.releaseBuild(buildSession);
    if (status.isOk()) status = released;
    return status.isOk() ? status : fail(detail, status);
  }

  private StatusCode buildPrivate(
      IndexedTransactionSession buildSession,
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      TableDescriptor descriptor,
      StatusDetail detail) {
    StatusCode status = privateBuild.createIntent(
        buildSession, reservation, plan, buildAdmission);
    TransactionState intentState = transactions.lastState();
    intentDurable = intentState == TransactionState.COMMITTED;
    if (intentDurable) prepared.markDurableBuildKnown();
    if (intentState == TransactionState.INDETERMINATE) {
      prepared.forgetIntentCommitOutcome();
    }
    if (status.isOk()) status = privateBuild.writeDefinition(
        buildSession, descriptor, reservation, plan, buildAdmission);
    if (status.isOk()) status = privateBuild.validateDefinition(
        buildSession, reservation, descriptorResult);
    if (status.isOk()) status = privateBuild.buildIndexes(
        buildSession, descriptor, reservation, plan, buildAdmission);
    if (status.isOk()) status = privateBuild.stageReady(
        publicationSession, descriptor, reservation);
    if (!status.isOk()) {
      if (intentDurable
          || intentState == TransactionState.INDETERMINATE) {
        intentDurable = false;
        return fail(detail, status);
      }
      return CatalogPreparedBuildCancellation.cancel(
          prepared, status, detail, cleaner, reservation.objectId(), intentDurable);
    }
    intentDurable = false;
    return succeed(detail);
  }

  StatusCode finish(
      CatalogPreparedTable prepared,
      TransactionState outcome,
      SchemaPin pin) {
    return completion.finish(prepared, outcome, pin);
  }

  private void reset(StatusDetail detail) {
    if (detail != null) detail.reset();
    plan.reset();
    reservation.reset();
    descriptorResult.reset();
    intentDurable = false;
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
