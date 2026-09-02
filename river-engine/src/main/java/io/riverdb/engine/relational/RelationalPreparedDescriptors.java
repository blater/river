package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;

/** Bounded session overlay and deferred cleanup owner for transactional catalog-v2 DDL. */
final class RelationalPreparedDescriptors {
  private final IndexedTransactionSession session;
  private final RelationalDatabaseServices services;
  private final RelationalDescriptorNames names;
  private final RelationalPreparedDescriptorEntries entries =
      new RelationalPreparedDescriptorEntries();
  private int count;
  private final int[] successorPublicationRows = {CatalogObjectHeadCodec.BYTES};

  RelationalPreparedDescriptors(
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices databaseServices,
      RelationalDescriptorNames descriptorNames) {
    session = indexedSession;
    services = databaseServices;
    names = descriptorNames;
  }

  StatusCode prepare(
      CharSequence name, TableDescriptor descriptor, StatusDetail detail) {
    StatusCode status = reserveEntry();
    if (!status.isOk()) return status;
    status = names.prepareRegistration(name);
    if (!status.isOk()) return status;
    RelationalPreparedDescriptorEntry entry = entries.at(count++);
    entry.begin(name, session.pendingMutationCount());
    status = services.prepareDescriptor(descriptor, session, entry.prepared, detail);
    if (!entry.prepared.isActive()) {
      entry.clear();
      count--;
      return status;
    }
    if (status.isOk()) status = names.register(name, entry.prepared.objectId());
    if (status.isOk()) entry.publishOverlay();
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
    StatusCode status = reserveEntry();
    if (status.isOk()) status = session.preflightPendingMutations(
        successorPublicationRows, 0, successorPublicationRows.length);
    if (!status.isOk()) return status;
    RelationalPreparedDescriptorEntry entry = entries.at(count++);
    entry.begin(name, session.pendingMutationCount());
    status = stagePublication
        ? services.prepareDescriptorSuccessor(
            current, proposed, session, entry.prepared, detail)
        : services.prepareDescriptorSuccessorBuild(
            current, proposed, session, entry.prepared, detail);
    if (!entry.prepared.isActive()) {
      entry.clear();
      count--;
      return status;
    }
    if (status.isOk()) entry.publishOverlay();
    return status;
  }

  StatusCode stageSuccessor(CharSequence name, StatusDetail detail) {
    for (int index = count - 1; index >= 0; index--) {
      RelationalPreparedDescriptorEntry entry = entries.at(index);
      if (entry.matches(name)) {
        return services.stagePreparedDescriptorSuccessor(
            session, entry.prepared, detail);
      }
    }
    return StatusCode.CONFLICT;
  }

  StatusCode open(CharSequence name, SchemaPin pin) {
    for (int index = count - 1; index >= 0; index--) {
      if (entries.at(index).matches(name)) return entries.at(index).prepared.borrow(pin);
    }
    return StatusCode.CONFLICT;
  }

  void hide(CharSequence name, int mutationStart) {
    for (int index = count - 1; index >= 0; index--) {
      RelationalPreparedDescriptorEntry entry = entries.at(index);
      if (entry.matches(name)) {
        entry.hide(mutationStart);
        return;
      }
    }
  }

  boolean replacesPublished(CharSequence name) {
    for (int index = count - 1; index >= 0; index--) {
      if (entries.at(index).replacesPublished(name)) return true;
    }
    return false;
  }

  boolean authorizes(SchemaPin pin) {
    for (int index = count - 1; index >= 0; index--) {
      if (entries.at(index).authorizes(pin)) return true;
    }
    return false;
  }

  void rollbackTo(int pendingMutations) {
    for (int index = 0; index < count; index++) entries.at(index).rollbackTo(pendingMutations);
  }

  boolean hasActive() {
    for (int index = 0; index < count; index++) {
      if (entries.at(index).prepared.isActive()) return true;
    }
    return false;
  }

  boolean hasVisible() {
    for (int index = 0; index < count; index++) {
      if (entries.at(index).isVisible()) return true;
    }
    return false;
  }

  StatusCode finish(TransactionState outcome) {
    if (outcome == TransactionState.INDETERMINATE) return StatusCode.RETRY;
    for (int index = 0; index < count; index++) entries.at(index).resolve(outcome);
    return retryFinalization();
  }

  StatusCode retryFinalization() {
    StatusCode first = StatusCode.OK;
    for (int index = 0; index < count; index++) {
      RelationalPreparedDescriptorEntry entry = entries.at(index);
      if (!entry.prepared.isActive()) continue;
      TransactionState resolution = entry.resolution();
      if (resolution != TransactionState.COMMITTED
          && resolution != TransactionState.ABORTED) continue;
      StatusCode status = services.finishDescriptor(entry.prepared, resolution);
      if (first.isOk() && !status.isOk()) first = status;
      if (!entry.prepared.isActive()) entry.clear();
    }
    if (!hasActive()) count = 0;
    return first;
  }

  private StatusCode reserveEntry() {
    return entries.reserve(count);
  }
}
