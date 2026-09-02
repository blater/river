package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.TransactionOutcome;

/** Owns the catalog-v2 descriptor overlay bound to one relational transaction session. */
final class RelationalDescriptorSession {
  private final RelationalSession owner;
  private final RelationalDatabaseServices services;
  private final RelationalDescriptorTableAccess rows;
  private final RelationalDescriptorNames names;
  private final RelationalPreparedDescriptors prepared;
  private final RelationalPreparedDescriptorOutcome outcome =
      new RelationalPreparedDescriptorOutcome();
  private final RelationalDescriptorDropPublications drops =
      new RelationalDescriptorDropPublications();
  private final SchemaPin namespacePin = new SchemaPin();
  private final StatusDetail namespaceDetail = new StatusDetail(128);

  RelationalDescriptorSession(
      RelationalSession relationalSession,
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices databaseServices) {
    owner = relationalSession;
    services = databaseServices;
    rows = new RelationalDescriptorTableAccess(
        owner, indexedSession, databaseServices);
    names = new RelationalDescriptorNames(indexedSession, services);
    prepared = new RelationalPreparedDescriptors(indexedSession, services, names);
  }

  RelationalDescriptorTableAccess rows() {
    return rows;
  }

  StatusCode prepare(
      CharSequence name, TableDescriptor descriptor, StatusDetail detail) {
    if (!owner.isTransactionActive() || descriptor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean acquired = !owner.schemaChangeActive();
    StatusCode status = owner.acquireSchemaChange();
    if (status.isOk()) status = ensureLegacyNameAbsent(name);
    if (status.isOk()) status = prepared.prepare(name, descriptor, detail);
    finishFailedPreparation(status, acquired);
    return status;
  }

  StatusCode prepareSuccessor(
      CharSequence name,
      SchemaPin current,
      TableDescriptor proposed,
      StatusDetail detail) {
    return prepareSuccessor(name, current, proposed, detail, true);
  }

  StatusCode prepareSuccessorBuild(
      CharSequence name,
      SchemaPin current,
      TableDescriptor proposed,
      StatusDetail detail) {
    return prepareSuccessor(name, current, proposed, detail, false);
  }

  private StatusCode prepareSuccessor(
      CharSequence name,
      SchemaPin current,
      TableDescriptor proposed,
      StatusDetail detail,
      boolean stagePublication) {
    if (detail != null) detail.reset();
    StatusCode status = RelationalDescriptorSuccessorInput.validate(
        owner.isTransactionActive(), current, proposed);
    if (status.isOk() && !current.isPublished()) status = StatusCode.CONFLICT;
    if (status.isOk() && !authorizes(current)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk()) {
      if (detail != null) detail.set(status);
      return status;
    }
    boolean acquired = !owner.schemaChangeActive();
    status = names.owns(name, current.tableId());
    if (status.isOk()) status = owner.acquireSchemaChange();
    if (status.isOk()) status = stagePublication
        ? prepared.prepareSuccessor(name, current, proposed, detail)
        : prepared.prepareSuccessorBuild(name, current, proposed, detail);
    finishFailedPreparation(status, acquired);
    return status;
  }

  StatusCode stagePreparedSuccessor(CharSequence name, StatusDetail detail) {
    if (!owner.isTransactionActive() || !owner.schemaChangeActive()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return prepared.stageSuccessor(name, detail);
  }

  StatusCode resolve(CharSequence name, SchemaPin pin, StatusDetail detail) {
    if (!owner.isTransactionActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = prepared.open(name, pin);
    if (status == StatusCode.CONFLICT) status = names.open(name, pin, detail);
    return status;
  }

  StatusCode drop(CharSequence name, SchemaPin current, StatusDetail detail) {
    if (!owner.isTransactionActive() || current == null || !current.isActive()
        || !authorizes(current)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean acquired = !owner.schemaChangeActive();
    StatusCode status = names.owns(name, current.tableId());
    if (status.isOk()) status = owner.acquireSchemaChange();
    if (status.isOk()) status = rows.checkDrop(current.descriptor());
    int mutationStart = owner.pendingMutationCount();
    boolean published = current.isPublished() || prepared.replacesPublished(name);
    if (status.isOk()) status = drops.prepare(published);
    if (status.isOk()) status = owner.preflightDescriptorDrop();
    if (status.isOk()) status = services.prepareDescriptorDrop(
        current, owner.indexedSession(), detail);
    if (status.isOk()) status = names.unregister(name);
    if (status.isOk()) drops.record(mutationStart, published);
    if (status.isOk()) prepared.hide(name, mutationStart);
    finishFailedPreparation(status, acquired);
    return status;
  }

  StatusCode rename(
      CharSequence currentName,
      CharSequence renamedName,
      SchemaPin current,
      StatusDetail detail) {
    if (!owner.isTransactionActive() || current == null || !current.isActive()
        || !current.isPublished() || !authorizes(current)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean acquired = !owner.schemaChangeActive();
    StatusCode status = ensureLegacyNameAbsent(renamedName);
    if (status.isOk()) status = owner.acquireSchemaChange();
    if (status.isOk()) status = names.rename(
        currentName, renamedName, current.tableId());
    finishFailedPreparation(status, acquired);
    if (!status.isOk() && detail != null) detail.set(status);
    return status;
  }

  StatusCode validateNames() {
    return owner.isTransactionActive()
        ? names.validateCommitted() : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode ensureNameAbsent(CharSequence name) {
    if (!owner.isTransactionActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
    namespaceDetail.reset();
    StatusCode status = resolve(name, namespacePin, namespaceDetail);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    StatusCode released = namespacePin.release();
    return released.isOk() ? StatusCode.CONFLICT : released;
  }

  StatusCode prepareBegin() {
    if (!prepared.hasActive()) return StatusCode.OK;
    StatusCode status = prepared.retryFinalization();
    return status.isOk() && prepared.hasActive() ? StatusCode.CONFLICT : status;
  }

  void rollbackTo(int pendingMutations) {
    prepared.rollbackTo(pendingMutations);
    drops.rollbackTo(pendingMutations);
  }

  boolean hasActive() {
    return prepared.hasActive();
  }

  boolean hasVisible() { return prepared.hasVisible(); }

  StatusCode closeActiveScan() {
    return rows.closeActiveScan();
  }

  StatusCode closeSession() {
    return rows.closeSession();
  }

  StatusCode finish(
      IndexedTransactionSession session,
      TransactionOutcome result,
      StatusCode status) {
    StatusCode finished = outcome.finish(
        prepared, session, result, status, drops.active());
    if (outcome.determinate()) drops.reset();
    return finished;
  }

  boolean committed() {
    return outcome.committed();
  }

  boolean determinate() {
    return outcome.determinate();
  }

  boolean publishSchemaChange() {
    return outcome.publishSchemaChange();
  }

  StatusCode reserveLogicalRowIds(
      long objectId, int count,
      io.riverdb.engine.table.IndexedLogicalRowIdReservation result) {
    return rows.reserveLogicalRowIds(objectId, count, result);
  }

  boolean authorizes(SchemaPin pin) {
    if (!owner.isTransactionActive() || pin == null || !pin.isActive()) return false;
    return pin.isPublished()
        ? services != null && services.owns(pin)
        : prepared.authorizes(pin);
  }

  private void finishFailedPreparation(StatusCode status, boolean acquired) {
    if (!status.isOk() && acquired && !prepared.hasActive()) {
      owner.cancelSchemaChange();
    }
  }

  private StatusCode ensureLegacyNameAbsent(CharSequence name) {
    return owner.ensureLegacyNameAbsent(name);
  }
}
