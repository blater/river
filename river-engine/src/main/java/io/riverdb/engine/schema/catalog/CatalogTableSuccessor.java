package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Private successor-definition build preserving the current published table generation. */
final class CatalogTableSuccessor {
  private final CatalogTransactions transactions;
  private final CatalogDefinitionWriter writer;
  private final CatalogSuccessorBuildFlow buildFlow;
  private final CatalogPrivateTableBuild privateBuild;
  private final CatalogTablePayloadPlan plan = new CatalogTablePayloadPlan();
  private final CatalogBuildAdmission admission = new CatalogBuildAdmission();
  private final CatalogReservation reservation = new CatalogReservation();
  private final CatalogReservation publication = new CatalogReservation();
  private final TableDescriptor.Result descriptor = new TableDescriptor.Result();
  private final CatalogSessionResult opened = new CatalogSessionResult();

  CatalogTableSuccessor(
      SchemaCache schemaCache,
      CatalogTransactions flow,
      CatalogIdAllocator allocator,
      CatalogDefinitionStore definitions,
      CatalogDefinitionWriter definitionWriter,
      CatalogObjectHeadStore heads,
      CatalogIntentStore intents,
      CatalogBuildCleaner buildCleaner) {
    transactions = flow;
    writer = definitionWriter;
    privateBuild = new CatalogPrivateTableBuild(
        flow, allocator, definitions, definitionWriter, heads, intents);
    buildFlow = new CatalogSuccessorBuildFlow(
        schemaCache, flow, privateBuild, buildCleaner);
  }

  StatusCode prepare(
      TableDescriptor current,
      TableDescriptor proposed,
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    return prepare(
        current, proposed, publicationSession, prepared, true, detail);
  }

  StatusCode prepareBuild(
      TableDescriptor current,
      TableDescriptor proposed,
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    return prepare(
        current, proposed, publicationSession, prepared, false, detail);
  }

  private StatusCode prepare(
      TableDescriptor current,
      TableDescriptor proposed,
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      boolean stagePublication,
      StatusDetail detail) {
    reset(detail);
    if (current == null || proposed == null || publicationSession == null
        || prepared == null || prepared.isActive()) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    StatusCode status = CatalogSuccessorProposalValidation.validate(
        current, proposed, detail);
    if (status.isOk()) status = writer.plan(proposed, plan);
    if (status.isOk()) status = admission.admit(plan);
    if (status.isOk()) status = transactions.openBuild(opened);
    if (!status.isOk()) return fail(detail, status);
    IndexedTransactionSession buildSession = opened.session();
    status = buildFlow.prepare(
        current, proposed, publicationSession, buildSession, plan, admission,
        reservation, descriptor, prepared, stagePublication, detail);
    StatusCode released = transactions.releaseBuild(buildSession);
    if (status.isOk()) status = released;
    return status.isOk() ? status : fail(detail, status);
  }

  StatusCode stagePrepared(
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    if (prepared == null || !prepared.isActive() || !prepared.replacesPublished()
        || prepared.publicationStaged()) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    publication.reset();
    prepared.restoreReservation(publication);
    StatusCode status = privateBuild.stageReady(
        publicationSession, prepared.descriptor(), publication);
    if (status.isOk()) prepared.markPublicationStaged();
    return status.isOk() ? status : fail(detail, status);
  }

  private void reset(StatusDetail detail) {
    if (detail != null) detail.reset();
    plan.reset();
    reservation.reset();
    descriptor.reset();
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status) {
    if (detail != null && detail.code() == StatusCode.OK) detail.set(status);
    return status;
  }
}
