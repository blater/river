package io.riverdb.bench.harness;

/** Local artifact write result. */
public enum ArtifactWriteStatus {
  WRITTEN,
  INVALID_DOCUMENT,
  TARGET_EXISTS,
  DIGEST_MISMATCH,
  WORKLOAD_GENERATION_FAILED,
  DUPLICATE_OUTPUT_NAME,
  ATOMIC_PUBLISH_UNAVAILABLE
}
