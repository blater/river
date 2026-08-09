package io.riverdb.bench.harness;

/** Package-private deterministic fault points for publication tests. */
enum ArtifactWriteFailure {
  NONE,
  AFTER_FIRST_PAYLOAD,
  CORRUPT_FIRST_WORKLOAD,
  BEFORE_PUBLISH
}
