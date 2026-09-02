package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogBuildIntent;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.catalog.CatalogObjectHead;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Resolves only definitions reached by a validated READY object head. */
final class CatalogDefinitionStore {
  private final CatalogIntentDefinitionValidation intentValidation =
      new CatalogIntentDefinitionValidation(this);
  private final CatalogTableAssemblyBuilder assembly = new CatalogTableAssemblyBuilder();
  private final CatalogDefinitionManifest manifest = new CatalogDefinitionManifest();
  private final CatalogDefinitionRecord child = new CatalogDefinitionRecord();
  private final CatalogObjectHead head = new CatalogObjectHead();
  private final HeapRowResult row = new HeapRowResult();
  private final IndexedScanCursor cursor = new IndexedScanCursor();
  private final IndexedScanResult scanned = new IndexedScanResult();
  private final ByteBuffer record = ByteBuffer.allocateDirect(
      CatalogDefinitionRecordCodec.MAX_RECORD_BYTES);
  private final CRC32C recordChecksum = new CRC32C();

  StatusCode validatePrivate(
      IndexedTransactionSession session, CatalogReservation reservation,
      TableDescriptor.Result result, StatusDetail detail) {
    record.clear();
    StatusCode status = CatalogObjectHeadCodec.encode(record, 0,
        CatalogObjectHeadCodec.STATE_READY, reservation.objectId(),
        reservation.schemaId(), reservation.catalogGeneration(),
        reservation.manifestRecordId(), recordChecksum);
    record.position(0);
    record.limit(CatalogObjectHeadCodec.BYTES);
    if (status.isOk()) {
      status = CatalogObjectHeadCodec.decode(record, 0, head, recordChecksum);
    }
    return status.isOk()
        ? assembleCurrent(session, reservation.objectId(), result, detail) : status;
  }

  StatusCode assemblePrivateIntent(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor.Result result) {
    if (session == null || intent == null || result == null
        || intent.nextChild() != intent.childCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    record.clear();
    StatusCode status = CatalogObjectHeadCodec.encode(record, 0,
        CatalogObjectHeadCodec.STATE_READY, intent.objectId(), intent.schemaId(),
        intent.catalogGeneration(), intent.manifestRecordId(), recordChecksum);
    record.position(0).limit(CatalogObjectHeadCodec.BYTES);
    if (status.isOk()) status = CatalogObjectHeadCodec.decode(record, 0, head, recordChecksum);
    return status.isOk()
        ? assembleCurrent(session, intent.objectId(), result, null) : status;
  }

  StatusCode readHead(IndexedTransactionSession session, long objectId) {
    StatusCode status = readAnyHead(session, objectId);
    if (!status.isOk()) return status;
    return head.state() == CatalogObjectHeadCodec.STATE_READY
        ? StatusCode.OK : StatusCode.CONFLICT;
  }

  StatusCode readAnyHead(IndexedTransactionSession session, long objectId) {
    head.reset();
    if (session == null || !CatalogKeyspace.validObjectHead(objectId)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.fetchByKey(
        CatalogKeyspace.OBJECT_HEAD_SPACE, objectId, row);
    if (!status.isOk()) return status;
    status = copyRow(CatalogObjectHeadCodec.BYTES);
    if (status.isOk()) {
      status = CatalogObjectHeadCodec.decode(record, 0, head, recordChecksum);
    }
    if (!status.isOk()) return status;
    return head.objectId() == objectId ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  long headSchemaId() { return head.schemaId(); }
  long headGeneration() { return head.catalogGeneration(); }
  long headManifestRecordId() { return head.manifestRecordId(); }
  int headState() { return head.state(); }

  StatusCode validatePredecessor(
      IndexedTransactionSession session, CatalogReservation reservation) {
    StatusCode status = readAnyHead(session, reservation.objectId());
    if (!status.isOk()) return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    return head.state() == CatalogObjectHeadCodec.STATE_READY
        && head.schemaId() == reservation.predecessorSchemaId()
        && head.catalogGeneration() == reservation.predecessorGeneration()
        && head.manifestRecordId() == reservation.predecessorManifestRecordId()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode readCurrentManifest(IndexedTransactionSession session, long objectId) {
    StatusCode status = readManifest(session);
    if (!status.isOk()) return referenced(status);
    return manifest.objectId() == objectId
        && manifest.schemaId() == head.schemaId()
        && manifest.catalogGeneration() == head.catalogGeneration()
        && manifest.catalogRecordId() == head.manifestRecordId()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  long currentRowLayoutId() { return manifest.rowLayoutId(); }

  long currentFirstChildRecordId() { return manifest.firstChildRecordId(); }

  int currentChildCount() { return manifest.childCount(); }

  StatusCode validateBuildingIntent(
      IndexedTransactionSession session, CatalogBuildIntent intent) {
    return intentValidation.validateBuilding(session, intent);
  }

  StatusCode validateReadyIntent(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor.Result result) {
    return intentValidation.validateReady(session, intent, result);
  }

  StatusCode validatePublishedIntent(
      IndexedTransactionSession session, CatalogBuildIntent intent,
      TableDescriptor.Result result) {
    return intentValidation.validatePublished(session, intent, result);
  }

  boolean matchesPredecessor(CatalogBuildIntent intent) {
    return head.state() == CatalogObjectHeadCodec.STATE_READY
        && head.schemaId() == intent.predecessorSchemaId()
        && head.catalogGeneration() == intent.predecessorGeneration()
        && head.manifestRecordId() == intent.predecessorManifestRecordId();
  }

  StatusCode loadHistorical(
      IndexedTransactionSession session, long objectId, long rowLayoutId,
      long catalogGeneration, TableDescriptor.Result result, StatusDetail detail) {
    if (session == null || !io.riverdb.format.catalog.CatalogKeyspace.validObjectHead(objectId)
        || rowLayoutId <= 0 || catalogGeneration < 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = findManifest(session, objectId, rowLayoutId, catalogGeneration);
    return status.isOk() ? assembleLoaded(session, result, detail) : status;
  }

  StatusCode assembleCurrent(
      IndexedTransactionSession session,
      long objectId,
      TableDescriptor.Result result,
      StatusDetail detail) {
    if (session == null || !CatalogKeyspace.validObjectHead(objectId) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = readCurrentManifest(session, objectId);
    if (!status.isOk()) return status;
    return assembleLoaded(session, result, detail);
  }

  private StatusCode assembleLoaded(
      IndexedTransactionSession session, TableDescriptor.Result result, StatusDetail detail) {
    StatusCode status = assembly.begin(manifest.objectId(), head, manifest);
    if (!status.isOk()) return status;
    for (int index = 0; index < manifest.childCount(); index++) {
      status = acceptChild(session, manifest.firstChildRecordId() + index);
      if (!status.isOk()) {
        assembly.reset();
        return referenced(status);
      }
    }
    return assembly.finish(result, detail);
  }

  private StatusCode findManifest(
      IndexedTransactionSession session, long objectId, long rowLayoutId, long generation) {
    StatusCode status = cursor.reset();
    if (status.isOk()) status = session.beginScan(CatalogKeyspace.DEFINITION_SPACE,
        Long.MIN_VALUE, CatalogKeyspace.DEFINITION_SPACE, Long.MAX_VALUE, cursor);
    long selectedId = 0;
    long selectedGeneration = 0;
    while (status.isOk()) {
      status = session.nextScan(cursor, scanned);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      if (scanned.keySpace() != CatalogKeyspace.DEFINITION_SPACE) {
        status = StatusCode.CORRUPTION;
        break;
      }
      StatusCode inspected = inspectManifest(scanned.row(), scanned.key());
      if (!inspected.isOk()) {
        status = inspected;
        break;
      }
      if (manifest.catalogRecordId() != 0 && manifest.objectId() == objectId
          && manifest.rowLayoutId() == rowLayoutId
          && (generation == 0 || manifest.catalogGeneration() == generation)
          && manifest.catalogGeneration() > selectedGeneration) {
        selectedId = manifest.catalogRecordId();
        selectedGeneration = manifest.catalogGeneration();
      }
    }
    if (cursor.isActive()) {
      StatusCode closed = session.closeScan(cursor);
      if (status.isOk()) status = closed;
    }
    if (!status.isOk() || selectedId == 0) {
      return status.isOk() ? StatusCode.CONFLICT : status;
    }
    status = readManifestRecord(session, selectedId);
    if (!status.isOk()) return referenced(status);
    return installHead();
  }

  StatusCode inspectManifest(HeapRowResult source, long key) {
    manifest.reset();
    if (source.length() != CatalogDefinitionManifestCodec.BYTES) return StatusCode.OK;
    StatusCode status = copyRow(source, CatalogDefinitionManifestCodec.BYTES);
    if (!status.isOk()) return status;
    if (!CatalogDefinitionManifestCodec.hasHeader(record, 0)) return StatusCode.OK;
    status = CatalogDefinitionManifestCodec.decode(record, 0, manifest, recordChecksum);
    if (!status.isOk()) return status;
    return manifest.catalogRecordId() == key ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  long inspectedManifestRecordId() { return manifest.catalogRecordId(); }

  long inspectedObjectId() { return manifest.objectId(); }

  StatusCode assembleInspected(
      IndexedTransactionSession session,
      TableDescriptor.Result result,
      StatusDetail detail) {
    StatusCode status = installHead();
    return status.isOk() ? assembleLoaded(session, result, detail) : status;
  }

  private StatusCode installHead() {
    record.clear();
    StatusCode status = CatalogObjectHeadCodec.encode(record, 0,
        CatalogObjectHeadCodec.STATE_READY, manifest.objectId(), manifest.schemaId(),
        manifest.catalogGeneration(), manifest.catalogRecordId(), recordChecksum);
    record.position(0);
    record.limit(CatalogObjectHeadCodec.BYTES);
    return status.isOk()
        ? CatalogObjectHeadCodec.decode(record, 0, head, recordChecksum) : status;
  }

  private StatusCode readManifest(IndexedTransactionSession session) {
    return readManifestRecord(session, head.manifestRecordId());
  }

  private StatusCode readManifestRecord(IndexedTransactionSession session, long recordId) {
    StatusCode status = session.fetchByKey(
        CatalogKeyspace.DEFINITION_SPACE, recordId, row);
    if (!status.isOk()) return status;
    status = copyRow(CatalogDefinitionManifestCodec.BYTES);
    return status.isOk()
        ? CatalogDefinitionManifestCodec.decode(record, 0, manifest, recordChecksum)
        : status;
  }

  private StatusCode acceptChild(IndexedTransactionSession session, long recordId) {
    StatusCode status = readChild(session, recordId);
    return status.isOk() ? assembly.accept(record, 0, row.length()) : status;
  }

  private StatusCode readChild(IndexedTransactionSession session, long recordId) {
    StatusCode status = session.fetchByKey(CatalogKeyspace.DEFINITION_SPACE, recordId, row);
    if (!status.isOk()) return status;
    int bytes = row.length();
    if (bytes < CatalogDefinitionRecordCodec.HEADER_BYTES
        || bytes > CatalogDefinitionRecordCodec.MAX_RECORD_BYTES) return StatusCode.CORRUPTION;
    status = copyRow(bytes);
    return status.isOk()
        ? CatalogDefinitionRecordCodec.decode(record, 0, bytes, child, recordChecksum)
        : status;
  }

  private StatusCode copyRow(int expectedBytes) {
    return copyRow(row, expectedBytes);
  }

  private StatusCode copyRow(HeapRowResult source, int expectedBytes) {
    if (source.length() != expectedBytes) return StatusCode.CORRUPTION;
    record.clear();
    StatusCode status = source.copyTo(record);
    record.flip();
    return status;
  }

  private static StatusCode referenced(StatusCode status) {
    return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
  }

}
