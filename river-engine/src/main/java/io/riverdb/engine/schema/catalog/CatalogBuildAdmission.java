package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.format.catalog.CatalogBuildByteCharge;

/** Exact per-definition durable record/count preflight before identity reservation. */
final class CatalogBuildAdmission {
  private long catalogBytes;

  StatusCode admit(CatalogTablePayloadPlan plan) {
    catalogBytes = 0;
    if (plan == null || plan.chunkCount() <= 0
        || plan.chunkCount() > SqlShapeLimits.MAX_SCHEMA_CHUNKS
        || plan.totalPayloadBytes() <= 0
        || plan.totalPayloadBytes() > SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long bytes = CatalogBuildByteCharge.value(
        plan.totalPayloadBytes(), plan.chunkCount());
    long maximum = CatalogBuildByteCharge.maximum();
    if (bytes > maximum) return StatusCode.RESOURCE_EXHAUSTED;
    catalogBytes = bytes;
    return StatusCode.OK;
  }

  long catalogBytes() { return catalogBytes; }
}
