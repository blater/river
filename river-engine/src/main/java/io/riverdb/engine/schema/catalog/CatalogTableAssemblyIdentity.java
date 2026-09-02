package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.format.catalog.CatalogDefinitionManifest;
import io.riverdb.format.catalog.CatalogDefinitionManifestCodec;
import io.riverdb.format.catalog.CatalogObjectHead;
import io.riverdb.format.catalog.CatalogObjectHeadCodec;

/** Validated immutable identity carried while one table generation is assembled. */
final class CatalogTableAssemblyIdentity {
  private long objectId;
  private long schemaId;
  private long rowLayoutId;
  private long generation;
  private int keyPartCount;

  StatusCode begin(
      long expectedObjectHeadKey, CatalogObjectHead head, CatalogDefinitionManifest manifest) {
    reset();
    if (head == null || manifest == null || expectedObjectHeadKey <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (head.state() != CatalogObjectHeadCodec.STATE_READY
        || head.objectId() != expectedObjectHeadKey || head.objectId() != manifest.objectId()
        || head.schemaId() != manifest.schemaId()
        || head.catalogGeneration() != manifest.catalogGeneration()
        || head.manifestRecordId() != manifest.catalogRecordId()
        || manifest.kind() != CatalogDefinitionManifestCodec.KIND_TABLE
        || manifest.columnCount() <= 0
        || manifest.columnCount() > SqlShapeLimits.MAX_TABLE_COLUMNS
        || manifest.logicalCount() != manifest.columnCount() + manifest.keyPartCount()) {
      return StatusCode.CORRUPTION;
    }
    objectId = manifest.objectId();
    schemaId = manifest.schemaId();
    rowLayoutId = manifest.rowLayoutId();
    generation = manifest.catalogGeneration();
    keyPartCount = manifest.keyPartCount();
    return StatusCode.OK;
  }

  void reset() {
    objectId = 0;
    schemaId = 0;
    rowLayoutId = 0;
    generation = 0;
    keyPartCount = 0;
  }

  long objectId() { return objectId; }
  long schemaId() { return schemaId; }
  long rowLayoutId() { return rowLayoutId; }
  long generation() { return generation; }
  int keyPartCount() { return keyPartCount; }
}
