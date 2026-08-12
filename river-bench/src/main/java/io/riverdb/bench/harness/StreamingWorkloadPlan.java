package io.riverdb.bench.harness;

import java.util.List;

/** Validated relational workload tables or a parameter error. */
public record StreamingWorkloadPlan(Status status, List<StreamingWorkloadArtifact> artifacts) {
  public enum Status {
    PLANNED,
    INVALID_SCALE,
    COUNT_OVERFLOW
  }

  public StreamingWorkloadPlan {
    artifacts = List.copyOf(artifacts);
  }

  public static StreamingWorkloadPlan planned(List<StreamingWorkloadArtifact> artifacts) {
    return new StreamingWorkloadPlan(Status.PLANNED, artifacts);
  }

  public static StreamingWorkloadPlan invalidScale() {
    return new StreamingWorkloadPlan(Status.INVALID_SCALE, List.of());
  }

  public static StreamingWorkloadPlan countOverflow() {
    return new StreamingWorkloadPlan(Status.COUNT_OVERFLOW, List.of());
  }
}
