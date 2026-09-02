package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogBuildIntentCodec;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;

/** Validates private-build intents against their authoritative head and manifest. */
final class CatalogIntentDefinitionValidation {
  private final CatalogDefinitionStore definitions;

  CatalogIntentDefinitionValidation(CatalogDefinitionStore store) {
    definitions = store;
  }

  StatusCode validateBuilding(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    StatusCode status = definitions.readAnyHead(session, intent.objectId());
    if (status == StatusCode.CONFLICT) {
      return intent.kind() == CatalogBuildIntentCodec.KIND_INITIAL
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    if (!status.isOk()) return status;
    if (definitions.headState() == CatalogObjectHeadCodec.STATE_TOMBSTONE) {
      return definitions.headGeneration() > intent.catalogGeneration()
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    if (intent.kind() == CatalogBuildIntentCodec.KIND_SUCCESSOR) {
      return definitions.matchesPredecessor(intent)
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    return matchesCurrent(intent, CatalogObjectHeadCodec.STATE_BUILDING)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode validateReady(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor.Result result) {
    if (invalid(session, intent, result)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = definitions.readAnyHead(session, intent.objectId());
    if (!status.isOk()) return referenced(status);
    if (!matchesCurrent(intent, CatalogObjectHeadCodec.STATE_READY)) {
      return StatusCode.CORRUPTION;
    }
    return validateManifestAndAssemble(session, intent, result);
  }

  StatusCode validatePublished(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor.Result result) {
    if (invalid(session, intent, result)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = definitions.readAnyHead(session, intent.objectId());
    if (!status.isOk()) return status;
    if (definitions.headState() == CatalogObjectHeadCodec.STATE_TOMBSTONE) {
      return definitions.headGeneration() > intent.catalogGeneration()
          ? StatusCode.CONFLICT : StatusCode.CORRUPTION;
    }
    if (intent.kind() == CatalogBuildIntentCodec.KIND_SUCCESSOR
        && definitions.matchesPredecessor(intent)) return StatusCode.CONFLICT;
    if (definitions.headState() == CatalogObjectHeadCodec.STATE_BUILDING) {
      return StatusCode.CONFLICT;
    }
    if (!matchesCurrent(intent, CatalogObjectHeadCodec.STATE_READY)) {
      return StatusCode.CORRUPTION;
    }
    return validateManifestAndAssemble(session, intent, result);
  }

  private StatusCode validateManifestAndAssemble(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor.Result result) {
    StatusCode status = definitions.readCurrentManifest(session, intent.objectId());
    if (!status.isOk()) return status;
    if (definitions.currentRowLayoutId() != intent.rowLayoutId()
        || definitions.currentFirstChildRecordId() != intent.firstChildRecordId()
        || definitions.currentChildCount() != intent.childCount()) {
      return StatusCode.CORRUPTION;
    }
    return definitions.assembleCurrent(session, intent.objectId(), result, null);
  }

  private boolean matchesCurrent(CatalogBuildIntent intent, int state) {
    return definitions.headState() == state
        && definitions.headSchemaId() == intent.schemaId()
        && definitions.headGeneration() == intent.catalogGeneration()
        && definitions.headManifestRecordId() == intent.manifestRecordId();
  }

  private static boolean invalid(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor.Result result) {
    return session == null || intent == null || result == null;
  }

  private static StatusCode referenced(StatusCode status) {
    return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
  }
}
