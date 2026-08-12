package io.riverdb.bench.harness;

/** Bounded workload-generation outcome. */
public record GenerationResult(Status status, WorkloadArtifact artifact) {
  public enum Status {
    GENERATED,
    INVALID_RECORD_COUNT
  }

  public static GenerationResult generated(WorkloadArtifact artifact) {
    return new GenerationResult(Status.GENERATED, artifact);
  }

  public static GenerationResult invalidRecordCount() {
    return new GenerationResult(Status.INVALID_RECORD_COUNT, null);
  }
}
