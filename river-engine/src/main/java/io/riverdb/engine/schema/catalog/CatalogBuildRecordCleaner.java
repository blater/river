package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogBuildIntentCodec;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Verifies intent ownership before deleting any unreachable catalog-definition record. */
final class CatalogBuildRecordCleaner {
  private final CatalogDefinitionStore definitions;
  private final TableDescriptor.Result descriptor = new TableDescriptor.Result();
  private final CatalogDefinitionManifest manifest = new CatalogDefinitionManifest();
  private final CatalogDefinitionRecord child = new CatalogDefinitionRecord();
  private final HeapRowResult row = new HeapRowResult();
  private final ByteBuffer bytes = ByteBuffer.allocateDirect(
      CatalogDefinitionRecordCodec.MAX_RECORD_BYTES);
  private final CRC32C checksum = new CRC32C();

  CatalogBuildRecordCleaner(CatalogDefinitionStore store) {
    definitions = store;
  }

  StatusCode validateReady(IndexedTransactionSession session, CatalogBuildIntent intent) {
    descriptor.reset();
    return definitions.validateReadyIntent(session, intent, descriptor);
  }

  StatusCode validatePublished(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    descriptor.reset();
    return definitions.validatePublishedIntent(session, intent, descriptor);
  }

  StatusCode requireUnpublished(IndexedTransactionSession session, CatalogBuildIntent intent) {
    return definitions.validateBuildingIntent(session, intent);
  }

  StatusCode loadPrivateDescriptor(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    descriptor.reset();
    return definitions.assemblePrivateIntent(session, intent, descriptor);
  }

  TableDescriptor privateDescriptor() { return descriptor.value(); }

  StatusCode deleteBuildingHead(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    StatusCode status = definitions.validateBuildingIntent(session, intent);
    if (!status.isOk()) return status;
    if (definitions.headState()
        == io.riverdb.format.catalog.CatalogObjectHeadCodec.STATE_TOMBSTONE) {
      return StatusCode.OK;
    }
    if (intent.kind() == CatalogBuildIntentCodec.KIND_SUCCESSOR) return StatusCode.OK;
    status = session.delete(CatalogKeyspace.OBJECT_HEAD_SPACE, intent.objectId());
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }

  StatusCode deleteRange(
      IndexedTransactionSession session, CatalogBuildIntent intent, int first, int count) {
    for (int offset = first; offset < first + count; offset++) {
      StatusCode status = offset == 0
          ? verifyManifest(session, intent) : verifyChild(session, intent, offset - 1);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode verifyManifest(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    StatusCode status = fetch(session, intent.manifestRecordId());
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk() || row.length() != CatalogDefinitionManifestCodec.BYTES) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    status = decodeManifest();
    if (!status.isOk() || manifest.catalogRecordId() != intent.manifestRecordId()
        || manifest.objectId() != intent.objectId() || manifest.schemaId() != intent.schemaId()
        || manifest.rowLayoutId() != intent.rowLayoutId()
        || manifest.catalogGeneration() != intent.catalogGeneration()
        || manifest.firstChildRecordId() != intent.firstChildRecordId()
        || manifest.childCount() != intent.childCount()) return StatusCode.CORRUPTION;
    return session.delete(CatalogKeyspace.DEFINITION_SPACE, intent.manifestRecordId());
  }

  private StatusCode verifyChild(
      IndexedTransactionSession session, CatalogBuildIntent intent, int ordinal) {
    long recordId = intent.firstChildRecordId() + ordinal;
    StatusCode status = fetch(session, recordId);
    if (status == StatusCode.CONFLICT) {
      return ordinal < intent.nextChild() ? StatusCode.CORRUPTION : StatusCode.OK;
    }
    if (!status.isOk() || ordinal >= intent.nextChild()
        || row.length() < CatalogDefinitionRecordCodec.HEADER_BYTES
        || row.length() > CatalogDefinitionRecordCodec.MAX_RECORD_BYTES) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    status = decodeChild();
    if (!status.isOk() || child.catalogRecordId() != recordId
        || child.objectId() != intent.objectId() || child.schemaId() != intent.schemaId()
        || child.catalogGeneration() != intent.catalogGeneration()
        || child.ordinal() != ordinal) return StatusCode.CORRUPTION;
    return session.delete(CatalogKeyspace.DEFINITION_SPACE, recordId);
  }

  private StatusCode fetch(IndexedTransactionSession session, long recordId) {
    return session.fetchByKey(CatalogKeyspace.DEFINITION_SPACE, recordId, row);
  }

  private StatusCode decodeManifest() {
    StatusCode status = copy();
    return status.isOk()
        ? CatalogDefinitionManifestCodec.decode(bytes, 0, manifest, checksum) : status;
  }

  private StatusCode decodeChild() {
    StatusCode status = copy();
    return status.isOk()
        ? CatalogDefinitionRecordCodec.decode(bytes, 0, row.length(), child, checksum) : status;
  }

  private StatusCode copy() {
    bytes.clear();
    StatusCode status = row.copyTo(bytes);
    bytes.flip();
    return status;
  }
}
