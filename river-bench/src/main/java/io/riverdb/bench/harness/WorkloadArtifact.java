package io.riverdb.bench.harness;

/** Deterministic workload bytes and the metadata needed to reproduce them. */
public record WorkloadArtifact(
    String name,
    int version,
    long seed,
    int recordCount,
    String config,
    byte[] tsv,
    String sha256) {
  public WorkloadArtifact {
    tsv = tsv.clone();
  }

  @Override
  public byte[] tsv() {
    return tsv.clone();
  }
}
