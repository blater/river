package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleIndexState;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogBuildIntent;

/** Validates published roots and advances bounded cleanup of private roots. */
final class CatalogTupleIndexCleanup {
  private final CatalogIntentStore intents;
  private final IndexedTupleIndexState state = new IndexedTupleIndexState();

  CatalogTupleIndexCleanup(CatalogIntentStore intentStore) {
    intents = intentStore;
  }

  StatusCode validatePublished(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor table) {
    if (!completeDefinition(intent, table)
        || intent.nextPhysicalIndex() != intent.physicalIndexCount()) {
      return StatusCode.CORRUPTION;
    }
    for (int ordinal = 0; ordinal < intent.physicalIndexCount(); ordinal++) {
      KeyDescriptor key = CatalogTableKeys.reservedPhysicalIndexAt(table, intent, ordinal);
      StatusCode status = read(session, key);
      if (!status.isOk()) return status;
      if (!CatalogTupleIndexIdentity.published(
          state, key, intent.objectId())) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  StatusCode advance(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor table) {
    if (!completeDefinition(intent, table)
        || intent.indexCleanupCursor() >= intent.nextPhysicalIndex()) {
      return StatusCode.CORRUPTION;
    }
    int ordinal = intent.indexCleanupCursor();
    KeyDescriptor key = CatalogTableKeys.reservedPhysicalIndexAt(table, intent, ordinal);
    StatusCode status = read(session, key);
    if (!status.isOk()) return status;
    if (!CatalogTupleIndexIdentity.owned(
        state, key, intent.objectId(), intent.schemaId())) {
      return StatusCode.CORRUPTION;
    }
    if (state.state() == TupleIndexRootRecordCodec.STATE_ABSENT) {
      return intents.updateIndexCleanup(session, intent, ordinal + 1);
    }
    status = session.preflightTupleIndexLifecycles(1);
    if (!status.isOk()) return status;
    if (state.state() == TupleIndexRootRecordCodec.STATE_BUILDING
        || state.state() == TupleIndexRootRecordCodec.STATE_READY) {
      if (intent.indexCleanupHorizon() != 0) return StatusCode.CORRUPTION;
      int horizon = session.tupleIndexCleanupHorizon();
      status = session.stageTupleIndexDropping(
          intent.objectId(), key.keyId(), key.keyId(),
          intent.schemaId(), key.shape());
      return status.isOk()
          ? intents.updateIndexCleanupHorizon(session, intent, horizon) : status;
    }
    int horizon = intent.indexCleanupHorizon();
    if (horizon < state.cleanupCursor()) return StatusCode.CORRUPTION;
    status = session.tupleIndexCleanupComplete(state, horizon);
    if (status == StatusCode.CONFLICT) return session.stageTupleIndexReclaim(
        intent.objectId(), key.keyId(), key.keyId(),
        intent.schemaId(), key.shape(), horizon);
    if (!status.isOk()) return status;
    status = session.stageTupleIndexAbsent(
        intent.objectId(), key.keyId(), key.keyId(),
        intent.schemaId(), key.shape(), horizon);
    return status.isOk()
        ? intents.updateIndexCleanup(session, intent, ordinal + 1) : status;
  }

  private StatusCode read(IndexedTransactionSession session, KeyDescriptor key) {
    return key == null ? StatusCode.CORRUPTION
        : session.readTupleIndexState(key.keyId(), state);
  }

  private static boolean completeDefinition(
      CatalogBuildIntent intent, TableDescriptor table) {
    return intent != null && table != null
        && intent.nextChild() == intent.childCount()
        && intent.physicalIndexCount() >= 0
        && intent.physicalIndexCount() <= intent.keyCount()
        && CatalogTableKeys.reservedPhysicalIndexCount(table, intent)
            == intent.physicalIndexCount();
  }
}
