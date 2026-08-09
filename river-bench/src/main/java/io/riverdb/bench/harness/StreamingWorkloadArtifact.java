package io.riverdb.bench.harness;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Re-runnable deterministic workload table whose bytes need not reside in memory. */
public final class StreamingWorkloadArtifact {
  @FunctionalInterface
  public interface Emitter {
    StreamingGenerationResult write(OutputStream output, byte[] scratch) throws IOException;
  }

  private final String name;
  private final int version;
  private final long seed;
  private final long recordCount;
  private final String schemaId;
  private final String config;
  private final Emitter emitter;

  public StreamingWorkloadArtifact(
      String name,
      int version,
      long seed,
      long recordCount,
      String schemaId,
      String config,
      Emitter emitter) {
    this.name = Objects.requireNonNull(name);
    this.version = version;
    this.seed = seed;
    this.recordCount = recordCount;
    this.schemaId = Objects.requireNonNull(schemaId);
    this.config = Objects.requireNonNull(config);
    this.emitter = Objects.requireNonNull(emitter);
  }

  public String name() {
    return name;
  }

  public int version() {
    return version;
  }

  public long seed() {
    return seed;
  }

  public long recordCount() {
    return recordCount;
  }

  public String schemaId() {
    return schemaId;
  }

  public String config() {
    return config;
  }

  public StreamingGenerationResult writeTo(OutputStream output, byte[] scratch)
      throws IOException {
    Objects.requireNonNull(output);
    Objects.requireNonNull(scratch);
    return emitter.write(output, scratch);
  }
}
