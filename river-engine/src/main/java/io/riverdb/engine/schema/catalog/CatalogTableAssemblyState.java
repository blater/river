package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.catalog.CatalogAssemblyValidator;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionRecord;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.format.catalog.CatalogObjectHead;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

final class CatalogTableAssemblyState {
  private final CatalogAssemblyValidator records = new CatalogAssemblyValidator();
  private final CatalogKeyAccumulator keys = new CatalogKeyAccumulator();
  private final CatalogDefinitionRecord decodedRecord = new CatalogDefinitionRecord();
  private final CRC32C childSetChecksum = new CRC32C();
  private final CRC32C recordChecksum = new CRC32C();
  private final CatalogIndexSchemaHashState indexSchemaHash = new CatalogIndexSchemaHashState();
  private final CatalogColumnAssembly columns = new CatalogColumnAssembly();
  private final CatalogTableAssemblyIdentity identity = new CatalogTableAssemblyIdentity();
  private int acceptedKeyParts;
  private boolean active;

  StatusCode begin(
      long expectedObjectHeadKey, CatalogObjectHead head, CatalogDefinitionManifest value) {
    reset();
    StatusCode status = identity.begin(expectedObjectHeadKey, head, value);
    if (!status.isOk()) return status;
    status = records.begin(value, childSetChecksum);
    if (!status.isOk()) {
      reset();
      return StatusCode.CORRUPTION;
    }
    status = columns.begin(value.columnCount(), value.payloadBytes());
    if (!status.isOk()) {
      reset();
      return status == StatusCode.RESOURCE_EXHAUSTED ? status : StatusCode.CORRUPTION;
    }
    active = true;
    return StatusCode.OK;
  }
  StatusCode accept(ByteBuffer encoded, int start, int recordBytes) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (encoded == null || start < 0 || recordBytes < 0) {
      reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = CatalogDefinitionRecordCodec.decode(
        encoded, start, recordBytes, decodedRecord, recordChecksum);
    if (!status.isOk()) return fail(status);
    CatalogDefinitionRecord record = decodedRecord;
    status = records.accept(record);
    if (!status.isOk()) return fail(status);
    int payloadStart = start + CatalogDefinitionRecordCodec.HEADER_BYTES;
    if (record.kind() == CatalogDefinitionRecordCodec.KIND_COLUMNS) {
      status = acceptColumns(record, encoded, payloadStart);
    } else if (record.kind() == CatalogDefinitionRecordCodec.KIND_KEY) {
      status = acceptKeys(record, encoded, payloadStart);
    } else {
      status = StatusCode.CORRUPTION;
    }
    return status.isOk() ? status : fail(status);
  }
  StatusCode finish(TableDescriptor.Result result, StatusDetail detail) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (detail != null) detail.reset();
    if (!active || !records.complete() || !columns.complete()
        || acceptedKeyParts != identity.keyPartCount()) {
      return fail(StatusCode.CORRUPTION, detail);
    }
    StatusCode status = freezeColumns();
    if (!status.isOk()) return fail(status, detail);
    CatalogKeyDescriptorArrays arrays;
    try {
      arrays = new CatalogKeyDescriptorArrays(keys.secondary(), keys.foreign());
    } catch (OutOfMemoryError error) {
      return fail(StatusCode.RESOURCE_EXHAUSTED, detail);
    }
    status = TableDescriptor.createCatalogBound(
        identity.objectId(), identity.schemaId(), identity.rowLayoutId(), identity.generation(),
        columns.value(), keys.primary(), arrays.secondary(),
        arrays.foreign(), result, detail);
    if (status.isOk() && !indexSchemaHash.matches(result.value())) {
      status = StatusCode.CORRUPTION;
    }
    if (status == StatusCode.INVALID_EXTERNAL_INPUT) {
      status = StatusCode.CORRUPTION;
      if (detail != null) detail.set(status).append("malformed catalog key identity");
    }
    if (!status.isOk()) result.reset();
    reset();
    return status;
  }
  void reset() {
    records.reset();
    keys.reset();
    decodedRecord.reset();
    childSetChecksum.reset();
    recordChecksum.reset();
    indexSchemaHash.reset();
    identity.reset();
    columns.reset();
    acceptedKeyParts = 0;
    active = false;
  }
  private StatusCode acceptColumns(
      CatalogDefinitionRecord record, ByteBuffer payload, int payloadStart) {
    return columns.accept(record, payload, payloadStart);
  }
  private StatusCode acceptKeys(
      CatalogDefinitionRecord record, ByteBuffer payload, int payloadStart) {
    if (!columns.complete()
        || record.logicalStart() != acceptedKeyParts) return StatusCode.CORRUPTION;
    StatusCode status = freezeColumns();
    if (!status.isOk()) return status;
    status = CatalogKeyPayloadCodec.decodeInto(payload, payloadStart,
        record.payloadBytes(), record.logicalCount(), columns.value(), keys, indexSchemaHash);
    if (status.isOk()) acceptedKeyParts += record.logicalCount();
    return status;
  }

  private StatusCode freezeColumns() {
    return columns.freeze();
  }

  private StatusCode fail(StatusCode status) {
    reset();
    return status;
  }

  private StatusCode fail(StatusCode status, StatusDetail detail) {
    if (detail != null) detail.set(status);
    reset();
    return status;
  }
}
