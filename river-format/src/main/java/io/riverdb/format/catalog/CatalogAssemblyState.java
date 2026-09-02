package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.util.zip.CRC32C;

final class CatalogAssemblyState {
  private final long[] acceptedWords =
      new long[(SqlShapeLimits.MAX_SCHEMA_CHUNKS + Long.SIZE - 1) / Long.SIZE];
  private int manifestKind;
  private long objectId;
  private long schemaId;
  private long generation;
  private long firstChildRecordId;
  private int childCount;
  private int expectedColumns;
  private int expectedKeyParts;
  private int expectedLogical;
  private int expectedBytes;
  private int expectedChecksum;
  private int acceptedCount;
  private int acceptedColumns;
  private int acceptedKeyParts;
  private int acceptedLogical;
  private int acceptedBytes;
  private int lastKind;
  private int nextLogical;
  private CRC32C childChecksum;
  private boolean active;

  StatusCode begin(CatalogDefinitionManifest manifest, CRC32C checksum) {
    reset();
    if (manifest == null || checksum == null || !validManifest(manifest)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    manifestKind = manifest.kind();
    objectId = manifest.objectId();
    schemaId = manifest.schemaId();
    generation = manifest.catalogGeneration();
    firstChildRecordId = manifest.firstChildRecordId();
    childCount = manifest.childCount();
    expectedColumns = manifest.columnCount();
    expectedKeyParts = manifest.keyPartCount();
    expectedLogical = manifest.logicalCount();
    expectedBytes = manifest.payloadBytes();
    expectedChecksum = manifest.childSetChecksum();
    childChecksum = checksum;
    childChecksum.reset();
    active = true;
    return StatusCode.OK;
  }

  StatusCode accept(CatalogDefinitionRecord child) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (child == null) {
      reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int ordinal = child.ordinal();
    int word = ordinal < 0 ? -1 : ordinal >>> 6;
    long bit = ordinal < 0 ? 0 : 1L << (ordinal & 63);
    if (child.catalogRecordId() != firstChildRecordId + acceptedCount
        || child.objectId() != objectId || child.schemaId() != schemaId
        || child.catalogGeneration() != generation || ordinal != acceptedCount
        || ordinal >= childCount || word >= acceptedWords.length
        || (acceptedWords[word] & bit) != 0 || !validChildKind(child.kind())
        || !validLogicalRange(child) || child.payloadBytes() <= 0
        || acceptedBytes > expectedBytes - child.payloadBytes()) {
      reset();
      return StatusCode.CORRUPTION;
    }
    acceptedWords[word] |= bit;
    acceptedCount++;
    acceptedLogical += child.logicalCount();
    acceptedBytes += child.payloadBytes();
    if (child.kind() == CatalogDefinitionRecordCodec.KIND_COLUMNS
        || child.kind() == CatalogDefinitionRecordCodec.KIND_STATISTICS) {
      acceptedColumns += child.logicalCount();
    } else if (child.kind() == CatalogDefinitionRecordCodec.KIND_KEY) {
      acceptedKeyParts += child.logicalCount();
    }
    CatalogDefinitionRecordCodec.updateChildSetChecksum(
        childChecksum, child.recordChecksum());
    lastKind = child.kind();
    nextLogical = child.logicalStart() + child.logicalCount();
    return StatusCode.OK;
  }

  boolean complete() {
    return active && acceptedCount == childCount && acceptedColumns == expectedColumns
        && acceptedKeyParts == expectedKeyParts && acceptedLogical == expectedLogical
        && acceptedBytes == expectedBytes
        && (int) childChecksum.getValue() == expectedChecksum;
  }

  void reset() {
    for (int index = 0; index < acceptedWords.length; index++) acceptedWords[index] = 0;
    manifestKind = 0;
    objectId = 0;
    schemaId = 0;
    generation = 0;
    firstChildRecordId = 0;
    childCount = 0;
    expectedColumns = 0;
    expectedKeyParts = 0;
    expectedLogical = 0;
    expectedBytes = 0;
    expectedChecksum = 0;
    acceptedCount = 0;
    acceptedColumns = 0;
    acceptedKeyParts = 0;
    acceptedLogical = 0;
    acceptedBytes = 0;
    lastKind = 0;
    nextLogical = 0;
    if (childChecksum != null) childChecksum.reset();
    childChecksum = null;
    active = false;
  }

  private boolean validLogicalRange(CatalogDefinitionRecord child) {
    if (lastKind == 0) {
      return child.logicalStart() == 0
          && (manifestKind == CatalogDefinitionManifestCodec.KIND_STATISTICS
              ? child.kind() == CatalogDefinitionRecordCodec.KIND_STATISTICS
              : child.kind() == CatalogDefinitionRecordCodec.KIND_COLUMNS);
    }
    return child.kind() == lastKind
        ? child.logicalStart() == nextLogical
        : child.kind() > lastKind && child.logicalStart() == 0;
  }

  private boolean validChildKind(int kind) {
    return manifestKind == CatalogDefinitionManifestCodec.KIND_TABLE
        ? kind >= CatalogDefinitionRecordCodec.KIND_COLUMNS
            && kind <= CatalogDefinitionRecordCodec.KIND_EXPRESSION
        : kind == CatalogDefinitionRecordCodec.KIND_STATISTICS;
  }

  private static boolean validManifest(CatalogDefinitionManifest manifest) {
    return (manifest.kind() == CatalogDefinitionManifestCodec.KIND_TABLE
        || manifest.kind() == CatalogDefinitionManifestCodec.KIND_STATISTICS)
        && manifest.objectId() > 0 && manifest.schemaId() > 0
        && manifest.rowLayoutId() > 0 && manifest.catalogGeneration() > 0
        && CatalogDefinitionManifestCodec.validRange(
            manifest.firstChildRecordId(), manifest.childCount())
        && manifest.childCount() <= SqlShapeLimits.MAX_SCHEMA_CHUNKS
        && manifest.columnCount() > 0 && manifest.keyPartCount() >= 0
        && manifest.logicalCount() >= manifest.columnCount()
        && manifest.payloadBytes() >= manifest.childCount()
        && manifest.payloadBytes() <= SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES;
  }
}
